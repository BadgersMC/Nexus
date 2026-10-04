package net.badgersmc.nexus.core

import net.badgersmc.nexus.annotations.ScopeType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import kotlin.reflect.KClass

/**
 * Registry for all component definitions in the Nexus container.
 * Thread-safe storage for bean definitions and singleton instances.
 */
class ComponentRegistry {

    private val definitions = ConcurrentHashMap<String, BeanDefinition>()
    private val singletons = ConcurrentHashMap<String, Any>()
    private val typeIndex = ConcurrentHashMap<KClass<*>, ConcurrentSkipListSet<String>>()

    /**
     * Register a bean definition with the container.
     * Logs a warning if a bean with the same name already exists (last-write wins).
     * This allows external beans registered before scanning to be overridden by
     * scanned components if they share the same name — but warns so it's not silent.
     */
    @Synchronized
    fun register(definition: BeanDefinition) {
        val existing = definitions[definition.name]
        require(existing == null || existing.type == definition.type) {
            "Duplicate bean name '${definition.name}': ${existing?.type?.qualifiedName} and ${definition.type.qualifiedName}"
        }
        if (existing != null) removeIndex(existing)
        definitions[definition.name] = definition
        addIndex(definition)
    }

    /** Atomically replace a definition and remove all of its former index edges. */
    @Synchronized
    fun replace(definition: BeanDefinition) {
        definitions[definition.name]?.let(::removeIndex)
        definitions[definition.name] = definition
        addIndex(definition)
    }

    /**
     * Get a bean definition by name.
     */
    fun getDefinition(name: String): BeanDefinition? {
        return definitions[name]
    }

    /**
     * Get all bean definitions of a specific type.
     */
    fun getDefinitionsByType(type: KClass<*>): List<BeanDefinition> {
        val names = typeIndex[type] ?: return emptyList()
        return names.mapNotNull { definitions[it] }
    }

    /**
     * Check if a bean with the given name exists.
     */
    fun contains(name: String): Boolean {
        return definitions.containsKey(name)
    }

    /**
     * Store a singleton instance.
     */
    fun putSingleton(name: String, instance: Any) {
        singletons[name] = instance
    }

    /**
     * Retrieve a singleton instance.
     */
    fun getSingleton(name: String): Any? {
        return singletons[name]
    }

    /**
     * Get all registered bean names.
     */
    fun getAllBeanNames(): Set<String> {
        return definitions.keys.toSortedSet()
    }

    /**
     * Get all singleton instances for lifecycle management.
     */
    fun getAllSingletons(): Collection<Any> {
        return singletons.toSortedMap().values
    }

    internal fun getAllDefinitions(): List<BeanDefinition> = definitions.values.sortedBy { it.name }

    internal fun removeSingleton(name: String): Any? = singletons.remove(name)

    private fun indexedTypes(type: KClass<*>): Set<KClass<*>> {
        val result = linkedSetOf(type)
        fun visit(javaType: Class<*>) {
            javaType.interfaces.sortedBy { it.name }.forEach {
                if (result.add(it.kotlin)) visit(it)
            }
            javaType.superclass?.takeUnless { it == Any::class.java || it == Object::class.java }?.let {
                if (result.add(it.kotlin)) visit(it)
            }
        }
        visit(type.java)
        return result
    }

    private fun addIndex(definition: BeanDefinition) {
        indexedTypes(definition.type).forEach { type ->
            typeIndex.computeIfAbsent(type) { ConcurrentSkipListSet() }.add(definition.name)
        }
    }

    private fun removeIndex(definition: BeanDefinition) {
        indexedTypes(definition.type).forEach { type ->
            typeIndex[type]?.let { names ->
                names.remove(definition.name)
                if (names.isEmpty()) typeIndex.remove(type, names)
            }
        }
    }

    /**
     * Clear all registrations (for testing or shutdown).
     */
    fun clear() {
        definitions.clear()
        singletons.clear()
        typeIndex.clear()
    }
}
