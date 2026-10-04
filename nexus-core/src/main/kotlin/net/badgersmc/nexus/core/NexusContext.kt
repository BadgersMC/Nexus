package net.badgersmc.nexus.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import net.badgersmc.nexus.annotations.ScopeType
import net.badgersmc.nexus.config.ConfigManager
import net.badgersmc.nexus.coroutines.NexusDispatchers
import net.badgersmc.nexus.coroutines.createNexusScope
import net.badgersmc.nexus.scanning.ComponentScanner
import java.nio.file.Path
import kotlin.reflect.KClass

/**
 * An active Nexus dependency injection context.
 * Use [prepare] to inspect verification before activation, or [create] for the
 * compatible one-call prepare/verify/activate path.
 */
class NexusContext internal constructor(
    private val classLoader: ClassLoader?,
    private val contextName: String,
    internal val verificationReceipt: VerificationReceipt? = null
) {
    private val registry = ComponentRegistry()
    private val factory = BeanFactory(registry)
    private var active = false
    private var closed = false

    val dispatchers: NexusDispatchers? = classLoader?.let { NexusDispatchers(it, contextName) }
    val scope: CoroutineScope? = dispatchers?.let { createNexusScope(it, contextName) }

    companion object {
        /** Scan and verify a context without instantiating scanned components. */
        fun prepare(
            basePackage: String,
            classLoader: ClassLoader,
            configDirectory: Path? = null,
            contextName: String = "nexus",
            externalBeans: Map<String, Any> = emptyMap()
        ): ContextCandidate {
            val definitions = mutableListOf<BeanDefinition>()
            definitions += infrastructureDefinitions()
            externalBeans.toSortedMap().forEach { (name, instance) ->
                definitions += BeanDefinition(
                    name, instance::class, ScopeType.SINGLETON, BeanOrigin.EXTERNAL_INSTANCE, { instance }
                )
            }
            if (configDirectory != null) {
                val manager = ConfigManager(configDirectory)
                definitions += BeanDefinition(
                    "configManager", ConfigManager::class, factory = { manager }, origin = BeanOrigin.CONFIGURATION
                )
                ComponentScanner().scanConfigFiles(basePackage, classLoader)
                    .sortedBy { it.qualifiedName }
                    .forEach { configClass ->
                        val instance = manager.load(configClass)
                        val name = configClass.simpleName!!.replaceFirstChar { it.lowercase() }
                        definitions += BeanDefinition(
                            name, configClass, ScopeType.SINGLETON, BeanOrigin.CONFIGURATION, { instance }
                        )
                    }
            }
            definitions += ComponentScanner().scan(basePackage, classLoader)
                .sortedWith(compareBy({ it.name }, { it.type.qualifiedName }))
                .map { it.copy(origin = BeanOrigin.SCANNED_COMPONENT) }
            return ContextCandidate(classLoader, contextName, definitions)
        }

        /** Prepare, verify and activate a scanned context. */
        fun create(
            basePackage: String,
            classLoader: ClassLoader,
            configDirectory: Path? = null,
            contextName: String = "nexus",
            externalBeans: Map<String, Any> = emptyMap()
        ): NexusContext = prepare(
            basePackage, classLoader, configDirectory, contextName, externalBeans
        ).activate()

        /** Create an active context for compatible manual registration. */
        fun create(classLoader: ClassLoader? = null, contextName: String = "nexus"): NexusContext {
            val context = NexusContext(classLoader, contextName)
            context.registerRuntimeInfrastructure()
            context.active = true
            return context
        }

        private fun infrastructureDefinitions(): List<BeanDefinition> = listOf(
            BeanDefinition(
                "nexusDispatchers", NexusDispatchers::class, factory = { error("runtime infrastructure") },
                origin = BeanOrigin.INFRASTRUCTURE
            ),
            BeanDefinition(
                "nexusScope", CoroutineScope::class, factory = { error("runtime infrastructure") },
                origin = BeanOrigin.INFRASTRUCTURE
            )
        )
    }

    internal fun activate(definitions: List<BeanDefinition>, receipt: VerificationReceipt): NexusContext {
        check(receipt.accepted) { "A rejected candidate cannot activate" }
        val runtimeDefinitions = definitions.sortedBy { it.name }.associate { definition ->
            val runtimeDefinition = if (definition.origin == BeanOrigin.SCANNED_COMPONENT) {
                definition.copy(factory = factory.createFactory(definition.type))
            } else {
                definition
            }
            definition.name to runtimeDefinition
        }
        val progress = ActivationProgress()
        try {
            runtimeDefinitions.values.filterNot { it.origin == BeanOrigin.INFRASTRUCTURE }
                .forEach(registry::register)
            registerRuntimeInfrastructure()
            installPreparedSingletons(receipt, runtimeDefinitions, progress)
            activateScannedSingletons(receipt, progress)
            progress.currentComponent = null
            active = true
            return this
        } catch (failure: Throwable) {
            progress.currentComponent?.takeUnless { it in progress.failed }?.let(progress.failed::add)
            val managedNames = runtimeDefinitions.values
                .filterNot { it.origin == BeanOrigin.INFRASTRUCTURE }
                .mapTo(mutableSetOf()) { it.name }
            progress.cleaned += cleanup(receipt.shutdownOrder.filter { it in managedNames })
            scope?.cancel("NexusContext activation failed")
            dispatchers?.shutdown()
            registry.clear()
            closed = true
            throw ContextActivationException(
                "Nexus context activation failed at '${progress.failed.lastOrNull() ?: "unknown"}'",
                failure,
                receipt,
                progress.constructed.toList(),
                progress.activated.toList(),
                progress.failed.toList(),
                progress.cleaned.distinct()
            )
        }
    }

    private fun installPreparedSingletons(
        receipt: VerificationReceipt,
        definitions: Map<String, BeanDefinition>,
        progress: ActivationProgress
    ) {
        receipt.activationOrder.mapNotNull(definitions::get)
            .filter { it.origin != BeanOrigin.SCANNED_COMPONENT && it.origin != BeanOrigin.INFRASTRUCTURE }
            .filter { it.scope == ScopeType.SINGLETON }
            .forEach { definition ->
                progress.currentComponent = definition.name
                val instance = definition.factory()
                progress.constructed += definition.name
                registry.putSingleton(definition.name, instance)
                progress.activated += definition.name
            }
    }

    private fun activateScannedSingletons(receipt: VerificationReceipt, progress: ActivationProgress) {
        receipt.activationOrder.mapNotNull(registry::getDefinition)
            .filter { it.origin == BeanOrigin.SCANNED_COMPONENT && it.scope == ScopeType.SINGLETON }
            .forEach { definition ->
                progress.currentComponent = definition.name
                try {
                    factory.activateSingleton(definition.name)
                    progress.constructed += definition.name
                    progress.activated += definition.name
                } catch (failure: SingletonActivationException) {
                    progress.constructed += failure.beanName
                    progress.failed += failure.beanName
                    if (failure.cleanupSucceeded) progress.cleaned += failure.beanName
                    throw failure
                }
            }
    }

    private fun registerRuntimeInfrastructure() {
        dispatchers?.let {
            registry.replace(BeanDefinition(
                "nexusDispatchers", NexusDispatchers::class, factory = { it }, origin = BeanOrigin.INFRASTRUCTURE
            ))
            registry.putSingleton("nexusDispatchers", it)
        }
        scope?.let {
            registry.replace(BeanDefinition(
                "nexusScope", CoroutineScope::class, factory = { it }, origin = BeanOrigin.INFRASTRUCTURE
            ))
            registry.putSingleton("nexusScope", it)
        }
    }

    fun getBean(name: String): Any {
        ensureActive()
        return factory.getBean(name)
    }

    fun <T : Any> getBean(type: KClass<T>): T {
        ensureActive()
        return factory.getBean(type)
    }

    inline fun <reified T : Any> getBean(): T = getBean(T::class)

    fun <T : Any> registerBean(name: String, type: KClass<T>, instance: T) {
        ensureActive()
        registry.register(BeanDefinition(name, type, factory = { instance }, origin = BeanOrigin.EXTERNAL_INSTANCE))
        registry.putSingleton(name, instance)
    }

    fun <T : Any> registerBean(
        name: String,
        type: KClass<T>,
        scope: ScopeType = ScopeType.SINGLETON,
        factory: () -> T
    ) {
        ensureActive()
        registry.register(BeanDefinition(name, type, scope, BeanOrigin.MANUAL_FACTORY, factory))
    }

    fun containsBean(name: String): Boolean = registry.contains(name)
    fun getBeanNames(): Set<String> = registry.getAllBeanNames()

    /** Close once, destroying dependants before their dependencies. */
    fun close() {
        if (closed) return
        closed = true
        scope?.cancel("NexusContext closing")
        val receiptOrder = verificationReceipt?.shutdownOrder.orEmpty()
        val remaining = registry.getAllBeanNames().filterNot { it in receiptOrder }.sortedDescending()
        cleanup(receiptOrder + remaining)
        dispatchers?.shutdown()
        registry.clear()
        active = false
    }

    fun getBeanFactory(): BeanFactory {
        ensureActive()
        return factory
    }

    private fun cleanup(order: List<String>): List<String> {
        val cleaned = mutableListOf<String>()
        order.distinct().forEach { name ->
            registry.removeSingleton(name)?.let { bean ->
                if (factory.invokePreDestroy(bean)) cleaned += name
            }
        }
        return cleaned
    }

    private fun ensureActive() {
        check(active && !closed) { "NexusContext is not active" }
    }
}

private class ActivationProgress {
    val constructed = mutableListOf<String>()
    val activated = mutableListOf<String>()
    val failed = mutableListOf<String>()
    val cleaned = mutableListOf<String>()
    var currentComponent: String? = null
}

/** A verified-but-not-yet-active context and its inspectable graph receipt. */
class ContextCandidate internal constructor(
    private val classLoader: ClassLoader,
    private val contextName: String,
    definitions: List<BeanDefinition>
) {
    private val definitions = definitions.toList()
    private val result = ContextVerifier.verify(this.definitions)
    val graph: DependencyGraph get() = result.graph
    val receipt: VerificationReceipt get() = result.receipt

    /** Activate exactly once, and only when verification accepted the graph. */
    @Synchronized
    fun activate(): NexusContext {
        if (!receipt.accepted) throw ContextVerificationException(receipt)
        check(!activated) { "ContextCandidate has already been activated" }
        activated = true
        return NexusContext(classLoader, contextName, receipt).activate(definitions, receipt)
    }

    private var activated = false
}
