package net.badgersmc.nexus.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ComponentRegistryTest {
    @Test
    fun `same definition registration has one deterministic type index edge`() {
        val registry = ComponentRegistry()
        val first = BeanDefinition("service", RegistryService::class) { RegistryService() }
        registry.register(first)
        registry.register(first.copy(factory = { RegistryService() }))
        assertEquals(listOf("service"), registry.getDefinitionsByType(RegistryContract::class).map { it.name })
    }

    @Test
    fun `replacement removes stale type index edges`() {
        val registry = ComponentRegistry()
        registry.register(BeanDefinition("service", RegistryService::class) { RegistryService() })
        registry.replace(BeanDefinition("service", ReplacementService::class) { ReplacementService() })
        assertEquals(emptyList(), registry.getDefinitionsByType(RegistryContract::class))
        assertEquals(listOf("service"), registry.getDefinitionsByType(ReplacementService::class).map { it.name })
    }
}

private interface RegistryContract
private class RegistryService : RegistryContract
private class ReplacementService
