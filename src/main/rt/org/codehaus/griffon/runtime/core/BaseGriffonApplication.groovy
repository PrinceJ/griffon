/*
 * Copyright 2008-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.codehaus.griffon.runtime.core

import groovy.beans.Bindable
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import org.codehaus.griffon.runtime.util.GriffonApplicationHelper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import griffon.core.*
import griffon.util.*

/**
 * Implements the basics for a skeleton GriffonApplication.<p>
 * It is recommended to use this class a starting point for any
 * custom GriffonApplication implementations, in conjunction with
 * Groovy's {@code @Delegate} AST transformation, as shown next:
 *
 * <pre>
 * class CustomGriffonApplication {
 *     {@code @Delegate} private final BaseGriffonApplication _base
 *     CustomGriffonApplication() {
 *         _base = new BaseGriffonApplication(this)
 *     }
 *     ...
 * }
 * </pre>
 * @author Danno Ferrin
 * @author Andres Almiray
 */
class BaseGriffonApplication implements GriffonApplication {
    Map<String, String> addonPrefixes = [:]

    Map<String, Map<String, String>> mvcGroups            = [:]
    Map<String, ? extends GriffonModel> models            = [:]
    Map<String, ? extends GriffonView> views              = [:]
    Map<String, ? extends GriffonController> controllers  = [:]
    Map<String, ? extends FactoryBuilderSupport> builders = [:]
    Map<String, Map<String, Object>> groups               = [:]

    Binding bindings = new Binding()
    ConfigObject config
    ConfigObject builderConfig
    Object eventsConfig
    AddonManager addonManager
    ArtifactManager artifactManager

    @Bindable Locale locale = Locale.getDefault()
    static final String[] EMPTY_ARGS = new String[0]
    protected final Object lock = new Object() 
    private ApplicationPhase phase = ApplicationPhase.INITIALIZE

    private final EventRouter eventRouter = new EventRouter()
    private final List<ShutdownHandler> shutdownHandlers = []
    final GriffonApplication appDelegate
    final String[] startupArgs
    private final Object shutdownLock = new Object()
    final Logger log

    BaseGriffonApplication(GriffonApplication appDelegate, String[] args = EMPTY_ARGS) {
        startupArgs = new String[args.length]
        System.arraycopy(args, 0, startupArgs, 0, args.length)

        this.appDelegate = appDelegate
        ApplicationHolder.application = appDelegate

        log = LoggerFactory.getLogger(appDelegate.class)
    }

    Map<String, ?> getAddons() {
        return addonManager?.addons ?: [:]
    }

    Metadata getMetadata() {
        return Metadata.current 
    }

    Class getAppConfigClass() {
        try {
            return getClass().classLoader.loadClass(GriffonApplication.Configuration.APPLICATION.name)
        } catch(ignored) {
           // ignore - might be null if properties file is preferred
        }
        return null
    }

    Class getConfigClass() {
        try{
           return getClass().classLoader.loadClass(GriffonApplication.Configuration.CONFIG.name)
        } catch(ignored) {
           // ignore - no additional config available
        }
        return null
    }

    Class getBuilderClass() {
        try {
            return getClass().classLoader.loadClass(GriffonApplication.Configuration.BUILDER.name)
        } catch(ignored) {
           // ignore - might be null if properties file is preferred
        }
        return null
    }

    Class getEventsClass() {
        try{
           return getClass().classLoader.loadClass(GriffonApplication.Configuration.EVENTS.name)
        } catch(ignored) {
           // ignore - no global event handler will be used
        }
        return null
    }

    Object getConfigValue(String key) {
        String[] keys = key.split(/\./);
        Map cfg = getConfig();
        for (int i = 0; i < keys.length - 1; i++) {
            cfg = (Map) cfg.get(keys[i]);
        }
        return cfg.get(keys[keys.length - 1]);
    }

    void initialize() {
        if(phase == ApplicationPhase.INITIALIZE) {
            GriffonApplicationHelper.prepare(appDelegate)
        }
    }

    void ready() {
        if(phase != ApplicationPhase.STARTUP) return

        phase = ApplicationPhase.READY
        event(GriffonApplication.Event.READY_START.name, [appDelegate])
        GriffonApplicationHelper.runScriptInsideUIThread(GriffonApplication.Lifecycle.READY.name, appDelegate)
        event(GriffonApplication.Event.READY_END.name, [appDelegate])
        phase = ApplicationPhase.MAIN
    }

    boolean canShutdown() {
        event(GriffonApplication.Event.SHUTDOWN_REQUESTED.name, [appDelegate])
        synchronized(shutdownLock) {
            for(handler in shutdownHandlers) {
                if(!handler.canShutdown(appDelegate)) {
                    event(GriffonApplication.Event.SHUTDOWN_ABORTED.name, [appDelegate])
                    if(log.isDebugEnabled()) {
                        try {
                            log.debug("Shutdown aborted by $handler")
                        }catch(UnsupportedOperationException uoe) {
                            log.debug('Shutdown aborted by a handler')
                        }
                    }
                    return false
                }
            }
        }
        return true
    }

    boolean shutdown() {
        // avoids reentrant calls to shutdown()
        // once permission to quit has been granted
        if(phase == ApplicationPhase.SHUTDOWN) return false

        if(!canShutdown()) return false
        log.info('Shutdown is in process')

        // signal that shutdown is in process
        phase = ApplicationPhase.SHUTDOWN
   
        // stage 1 - alert all app event handlers
        // wait for all handlers to complete before proceeding
        // with stage #2 if and only if the current thread is
        // the ui thread
        log.debug('Shutdown stage 1: notify all event listeners')
        CountDownLatch latch = isUIThread() ? new CountDownLatch(1) : null
        addApplicationEventListener(GriffonApplication.Event.SHUTDOWN_START.name) {latch?.countDown()}
        event(GriffonApplication.Event.SHUTDOWN_START.name, [appDelegate])
        latch?.await()
  
        // stage 2 - alert all shutdown handlers
        log.debug('Shutdown stage 2: notify all shutdown handlers')
        synchronized(shutdownLock) {
            for(handler in shutdownHandlers) {
                handler.onShutdown(appDelegate)
            }
        }
 
        // stage 3 - destroy all mvc groups
        List mvcNames = []
        mvcNames.addAll(groups.keySet())
        log.debug('Shutdown stage 3: destroy all MVC groups')
        mvcNames.each { destroyMVCGroup(it) }

        // stage 4 - call shutdown script
        log.debug('Shutdown stage 4: execute Shutdown script')
        GriffonApplicationHelper.runScriptInsideUIThread(GriffonApplication.Lifecycle.SHUTDOWN.name, appDelegate)

        true
    }

    void startup() {
        if(phase != ApplicationPhase.INITIALIZE) return

        phase = phase.STARTUP
        event(GriffonApplication.Event.STARTUP_START.name, [appDelegate])
    
        if(log.infoEnabled) log.info("Initializing all startup groups: ${config.application.startupGroups}")
        config.application.startupGroups.each {group -> createMVCGroup(group) }
        GriffonApplicationHelper.runScriptInsideUIThread(GriffonApplication.Lifecycle.STARTUP.name, appDelegate)

        event(GriffonApplication.Event.STARTUP_END.name, [appDelegate])
    }

    void event(String eventName) {
        eventRouter.publish(eventName, [])
    }

    void event(String eventName, List params) {
        eventRouter.publish(eventName, params)
    }

    void eventOutside(String eventName) {
        eventRouter.publishOutside(eventName, [])
    }

    void eventOutside(String eventName, List params) {
        eventRouter.publishOutside(eventName, params)
    }
    
    void eventAsync(String eventName) {
        eventRouter.publishAsync(eventName, [])
    }

    void eventAsync(String eventName, List params) {
        eventRouter.publishAsync(eventName, params)
    }

    void addApplicationEventListener(listener) {
       eventRouter.addEventListener(listener)
    }

    void removeApplicationEventListener(listener) {
       eventRouter.removeEventListener(listener)
    }

    void addApplicationEventListener(String eventName, Closure listener) {
       eventRouter.addEventListener(eventName, listener)
    }

    void removeApplicationEventListener(String eventName, Closure listener) {
       eventRouter.removeEventListener(eventName, listener)
    }

    void addApplicationEventListener(String eventName, RunnableWithArgs listener) {
       eventRouter.addEventListener(eventName, listener)
    }

    void removeApplicationEventListener(String eventName, RunnableWithArgs listener) {
       eventRouter.removeEventListener(eventName, listener)
    }

    void addMvcGroup(String mvcType, Map<String, String> mvcPortions) {
       mvcGroups[mvcType] = mvcPortions
    }

    Object createApplicationContainer() {
        null
    }

    void addShutdownHandler(ShutdownHandler handler) {
        if(handler && !shutdownHandlers.contains(handler)) shutdownHandlers << handler
    }

    void removeShutdownHandler(ShutdownHandler handler) {
        if(handler) shutdownHandlers.remove(handler)
    }

    ApplicationPhase getPhase() {
        synchronized(lock) {
            this.@phase
        }
    }
    
    protected void setPhase(ApplicationPhase phase) {
        synchronized(lock) {
            this.@phase = phase
        }
    }

    // -----------------------

    boolean isUIThread() {
        UIThreadManager.instance.isUIThread()
    }

    void execAsync(Runnable runnable) {
        UIThreadManager.instance.executeAsync(runnable)
    }

    void execSync(Runnable runnable) {
        UIThreadManager.instance.executeSync(runnable)
    }

    void execOutside(Runnable runnable) {
        UIThreadManager.instance.executeOutside(runnable)
    }

    Future execFuture(ExecutorService executorService, Closure closure) {
        UIThreadManager.instance.executeFuture(executorService, closure)
    }

    Future execFuture(Closure closure) {
        UIThreadManager.instance.executeFuture(closure)
    }

    Future execFuture(ExecutorService executorService, Callable callable) {
        UIThreadManager.instance.executeFuture(executorService, callable)
    }

    Future execFuture(Callable callable) {
        UIThreadManager.instance.executeFuture(callable)
    }

    Object newInstance(Class clazz, String type) {
        GriffonApplicationHelper.newInstance(appDelegate, clazz, type)
    }

    Map<String, Object> buildMVCGroup(String mvcType) {
        GriffonApplicationHelper.buildMVCGroup(appDelegate, [:], mvcType, mvcType)
    }

    Map<String, Object> buildMVCGroup(String mvcType, String mvcName) {
        GriffonApplicationHelper.buildMVCGroup(appDelegate, [:], mvcType, mvcName)
    }

    Map<String, Object> buildMVCGroup(Map<String, Object> args, String mvcType) {
        GriffonApplicationHelper.buildMVCGroup(appDelegate, args, mvcType, mvcType)
    }

    Map<String, Object> buildMVCGroup(Map<String, Object> args, String mvcType, String mvcName) {
        GriffonApplicationHelper.buildMVCGroup(appDelegate, args, mvcType, mvcName)
    }

    List<? extends GriffonMvcArtifact> createMVCGroup(String mvcType) {
        GriffonApplicationHelper.createMVCGroup(appDelegate, mvcType)
    }

    List<? extends GriffonMvcArtifact> createMVCGroup(Map<String, Object> args, String mvcType) {
        GriffonApplicationHelper.createMVCGroup(appDelegate, args, mvcType)
    }

    List<? extends GriffonMvcArtifact> createMVCGroup(String mvcType, Map<String, Object> args) {
        GriffonApplicationHelper.createMVCGroup(appDelegate, args, mvcType)
    }

    List<? extends GriffonMvcArtifact> createMVCGroup(String mvcType, String mvcName) {
        GriffonApplicationHelper.createMVCGroup(appDelegate, mvcType, mvcName)
    }

    List<? extends GriffonMvcArtifact> createMVCGroup(Map<String, Object> args, String mvcType, String mvcName) {
        GriffonApplicationHelper.createMVCGroup(appDelegate, args, mvcType, mvcName)
    }

    List<? extends GriffonMvcArtifact> createMVCGroup(String mvcType, String mvcName, Map<String, Object> args) {
        GriffonApplicationHelper.createMVCGroup(appDelegate, args, mvcType, mvcName)
    }

    void destroyMVCGroup(String mvcName) {
        GriffonApplicationHelper.destroyMVCGroup(appDelegate, mvcName)
    }

    void withMVCGroup(String mvcType, Closure handler) {
        withMVCGroup(mvcType, mvcType, [:], handler)
    }

    void withMVCGroup(String mvcType, String mvcName, Closure handler) {
        withMVCGroup(mvcType, mvcName, [:], handler)
    }

    void withMVCGroup(String mvcType, Map<String, Object> args, Closure handler) {
        withMVCGroup(mvcType, mvcType, args, handler)
    }

    void withMVCGroup(String mvcType, String mvcName, Map<String, Object> args, Closure handler) {
        GriffonApplicationHelper.withMVCGroup(appDelegate, mvcType, mvcName, args, handler)
    }

    public <M extends GriffonModel, V extends GriffonView, C extends GriffonController> void withMVCGroup(String mvcType, MVCClosure<M, V, C> handler) {
        withMVCGroup(mvcType, mvcType, [:], handler)
    }

    public <M extends GriffonModel, V extends GriffonView, C extends GriffonController> void withMVCGroup(String mvcType, String mvcName, MVCClosure<M, V, C> handler) {
        withMVCGroup(mvcType, mvcName, [:], handler)
    }

    public <M extends GriffonModel, V extends GriffonView, C extends GriffonController> void withMVCGroup(String mvcType, Map<String, Object> args, MVCClosure<M, V, C> handler) {
        withMVCGroup(mvcType, mvcType, args, handler)
    }

    public <M extends GriffonModel, V extends GriffonView, C extends GriffonController> void withMVCGroup(String mvcType, String mvcName, Map<String, Object> args, MVCClosure<M, V, C> handler) {
        GriffonApplicationHelper.withMVCGroup(appDelegate, mvcType, mvcName, args, handler)
    }
}
