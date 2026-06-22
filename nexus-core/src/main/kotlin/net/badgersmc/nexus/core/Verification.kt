package net.badgersmc.nexus.core

import net.badgersmc.nexus.annotations.PostConstruct
import net.badgersmc.nexus.annotations.PreDestroy
import net.badgersmc.nexus.annotations.Qualifier
import java.security.MessageDigest
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KVisibility
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.full.primaryConstructor

/** Immutable dependency graph produced before context activation. */
data class DependencyGraph(
    val nodes: List<ComponentNode>,
    val edges: List<DependencyEdge>
)

/** Verification-relevant metadata for one candidate component. */
data class ComponentNode(
    val name: String,
    val type: String,
    val scope: String,
    val constructor: String?,
    val qualifiers: List<String>,
    val lifecycleMethods: List<String>,
    val origin: BeanOrigin
)

/** One constructor dependency and its resolution result. */
data class DependencyEdge(
    val requestingBean: String,
    val parameter: String,
    val requestedType: String,
    val qualifier: String?,
    val resolvedProvider: String?
)

enum class VerificationStatus { ACCEPTED, REJECTED }

/** A structured problem found while verifying a context candidate. */
sealed class VerificationIssue(
    val code: String,
    open val beanName: String,
    open val detail: String
) {
    data class MissingDependency(
        override val beanName: String,
        val parameter: String,
        val requestedType: String,
        val qualifier: String?
    ) : VerificationIssue(
        "MISSING_DEPENDENCY", beanName,
        "Bean '$beanName' parameter '$parameter' requires '$requestedType'" +
            (qualifier?.let { " qualified as '$it'" } ?: "") + "."
    )

    data class AmbiguousDependency(
        override val beanName: String,
        val parameter: String,
        val requestedType: String,
        val providers: List<String>
    ) : VerificationIssue(
        "AMBIGUOUS_DEPENDENCY", beanName,
        "Bean '$beanName' parameter '$parameter' has multiple '$requestedType' providers: ${providers.joinToString()}."
    )

    data class CircularDependency(val cycle: List<String>) : VerificationIssue(
        "CIRCULAR_CONSTRUCTOR_DEPENDENCY", cycle.first(),
        "Circular constructor dependency: ${cycle.joinToString(" -> ")}."
    )

    data class DuplicateBeanName(
        override val beanName: String,
        val types: List<String>
    ) : VerificationIssue(
        "DUPLICATE_BEAN_NAME", beanName,
        "Bean name '$beanName' is declared with incompatible types: ${types.joinToString()}."
    )

    data class DuplicateTypeIndexEntry(
        override val beanName: String,
        val indexedType: String
    ) : VerificationIssue(
        "DUPLICATE_TYPE_INDEX_ENTRY", beanName,
        "Bean '$beanName' is indexed more than once for '$indexedType'."
    )

    data class NoUsableConstructor(
        override val beanName: String,
        val componentType: String
    ) : VerificationIssue(
        "NO_USABLE_CONSTRUCTOR", beanName,
        "Component '$beanName' ($componentType) has no primary constructor Nexus can invoke."
    )

    data class UnresolvableParameterType(
        override val beanName: String,
        val parameter: String,
        val renderedType: String
    ) : VerificationIssue(
        "UNRESOLVABLE_PARAMETER_TYPE", beanName,
        "Bean '$beanName' parameter '$parameter' has an unresolvable type '$renderedType'."
    )

    data class InvalidLifecycleMethod(
        override val beanName: String,
        val method: String,
        val reason: String
    ) : VerificationIssue(
        "INVALID_LIFECYCLE_METHOD", beanName,
        "Lifecycle method '$beanName.$method' is not safely invokable: $reason."
    )

    data class ContradictoryQualifier(
        override val beanName: String,
        val parameter: String,
        val qualifier: String,
        val requestedType: String,
        val providerType: String
    ) : VerificationIssue(
        "CONTRADICTORY_QUALIFIER", beanName,
        "Qualifier '$qualifier' for '$beanName.$parameter' selects '$providerType', not requested '$requestedType'."
    )
}

/** Stable, immutable result of candidate graph verification. */
data class VerificationReceipt(
    val schemaVersion: Int = SCHEMA_VERSION,
    val status: VerificationStatus,
    val graphHash: String,
    val componentCount: Int,
    val dependencyCount: Int,
    val issues: List<VerificationIssue>,
    val activationOrder: List<String>,
    val shutdownOrder: List<String>
) {
    val accepted: Boolean get() = status == VerificationStatus.ACCEPTED

    /** Deterministic JSON representation suitable for persistence and comparison. */
    fun toJson(): String = buildString {
        append("{\"schemaVersion\":").append(schemaVersion)
        append(",\"status\":\"").append(status).append('"')
        append(",\"graphHash\":\"").append(graphHash).append('"')
        append(",\"componentCount\":").append(componentCount)
        append(",\"dependencyCount\":").append(dependencyCount)
        append(",\"issues\":[")
        issues.forEachIndexed { index, issue ->
            if (index > 0) append(',')
            append(issue.toJson())
        }
        append("],\"activationOrder\":").append(toJsonStrings(activationOrder))
        append(",\"shutdownOrder\":").append(toJsonStrings(shutdownOrder)).append('}')
    }

    companion object {
        const val SCHEMA_VERSION: Int = 2
    }
}

/** Verified graph plus its receipt. */
data class VerificationResult(val graph: DependencyGraph, val receipt: VerificationReceipt)

/** Builds and verifies a complete dependency graph without invoking bean factories. */
object ContextVerifier {
    fun verify(definitions: Collection<BeanDefinition>): VerificationResult {
        val sortedDefinitions = definitions.sortedWith(compareBy({ it.name }, { typeName(it.type) }, { it.origin.name }))
        val issues = mutableListOf<VerificationIssue>()
        val unique = selectDefinitions(sortedDefinitions, issues)
        val nodes = buildNodes(unique.values, issues)
        val edges = buildEdges(unique, issues).sortedWith(
            compareBy({ it.requestingBean }, { it.parameter }, { it.requestedType }, { it.qualifier ?: "" })
        )
        issues += findCycles(unique.keys, edges)
        val sortedIssues = issues.distinctBy { "${it.code}|${it.beanName}|${it.detail}" }
            .sortedWith(compareBy({ it.code }, { it.beanName }, { it.detail }))
        val graph = DependencyGraph(nodes.sortedBy { it.name }, edges)
        val activationOrder = if (sortedIssues.none { it is VerificationIssue.CircularDependency }) {
            topologicalOrder(unique.keys, edges)
        } else emptyList()
        val receipt = VerificationReceipt(
            status = if (sortedIssues.isEmpty()) VerificationStatus.ACCEPTED else VerificationStatus.REJECTED,
            graphHash = sha256(canonicalGraph(graph)),
            componentCount = nodes.size,
            dependencyCount = edges.size,
            issues = sortedIssues.toList(),
            activationOrder = activationOrder,
            shutdownOrder = activationOrder.asReversed()
        )
        return VerificationResult(graph, receipt)
    }

    private fun selectDefinitions(
        definitions: List<BeanDefinition>,
        issues: MutableList<VerificationIssue>
    ): Map<String, BeanDefinition> = buildMap {
        definitions.groupBy { it.name }.toSortedMap().forEach { (name, sameName) ->
            val types = sameName.map { typeName(it.type) }.distinct().sorted()
            if (types.size > 1) issues += VerificationIssue.DuplicateBeanName(name, types)
            if (sameName.size > 1 && types.size == 1) {
                issues += VerificationIssue.DuplicateTypeIndexEntry(name, types.single())
            }
            put(name, sameName.first())
        }
    }

    private fun buildNodes(
        definitions: Collection<BeanDefinition>,
        issues: MutableList<VerificationIssue>
    ): List<ComponentNode> = definitions.map { definition ->
            val constructor = definition.type.primaryConstructor
            if (definition.origin == BeanOrigin.SCANNED_COMPONENT &&
                (constructor == null || constructor.visibility != KVisibility.PUBLIC)
            ) {
                issues += VerificationIssue.NoUsableConstructor(definition.name, typeName(definition.type))
            }
            val lifecycle = lifecycleMethods(definition.type)
            lifecycle.forEach { method -> validateLifecycle(definition.name, method)?.let(issues::add) }
            ComponentNode(
                definition.name,
                typeName(definition.type),
                definition.scope.name,
                constructor?.let(::constructorSignature),
                constructor?.parameters.orEmpty().mapNotNull { parameter ->
                    parameter.findAnnotation<Qualifier>()?.value?.let { qualifier ->
                        "${parameter.name ?: "?"}=$qualifier"
                    }
                }.sorted(),
                lifecycle.map(::functionSignature),
                definition.origin
            )
        }

    private fun buildEdges(
        definitions: Map<String, BeanDefinition>,
        issues: MutableList<VerificationIssue>
    ): List<DependencyEdge> {
        val typeIndex = buildTypeIndex(definitions.values)
        val edges = mutableListOf<DependencyEdge>()
        definitions.values.forEach { definition ->
            if (definition.origin != BeanOrigin.SCANNED_COMPONENT) return@forEach
            definition.type.primaryConstructor
                ?.takeIf { it.visibility == KVisibility.PUBLIC }
                ?.parameters?.forEachIndexed { index, parameter ->
                val parameterName = parameter.name ?: "arg$index"
                val requested = parameter.type.classifier as? KClass<*>
                if (requested == null) {
                    issues += VerificationIssue.UnresolvableParameterType(
                        definition.name, parameterName, parameter.type.toString()
                    )
                    edges += DependencyEdge(definition.name, parameterName, parameter.type.toString(), null, null)
                    return@forEachIndexed
                }
                val requestedName = typeName(requested)
                val qualifier = parameter.findAnnotation<Qualifier>()?.value
                val provider = if (qualifier != null) {
                    val selected = definitions[qualifier]
                    when {
                        selected == null -> issues += VerificationIssue.MissingDependency(
                            definition.name, parameterName, requestedName, qualifier
                        )
                        !requested.java.isAssignableFrom(selected.type.java) -> issues +=
                            VerificationIssue.ContradictoryQualifier(
                                definition.name, parameterName, qualifier, requestedName, typeName(selected.type)
                            )
                    }
                    selected?.takeIf { requested.java.isAssignableFrom(it.type.java) }
                } else {
                    val providers = typeIndex[requested].orEmpty().sorted()
                    when (providers.size) {
                        0 -> issues += VerificationIssue.MissingDependency(
                            definition.name, parameterName, requestedName, null
                        )
                        1 -> Unit
                        else -> issues += VerificationIssue.AmbiguousDependency(
                            definition.name, parameterName, requestedName, providers
                        )
                    }
                    providers.singleOrNull()?.let(definitions::get)
                }
                edges += DependencyEdge(definition.name, parameterName, requestedName, qualifier, provider?.name)
            }
        }
        return edges
    }

    private fun validateLifecycle(bean: String, method: KFunction<*>): VerificationIssue? {
        val valueParameters = method.parameters.count { it.kind == KParameter.Kind.VALUE }
        val reason = when {
            valueParameters != 0 -> "callbacks must not declare arguments"
            method.visibility != KVisibility.PUBLIC -> "callbacks must be public"
            method.isAbstract -> "callbacks must not be abstract"
            else -> return null
        }
        return VerificationIssue.InvalidLifecycleMethod(bean, functionSignature(method), reason)
    }

    private fun lifecycleMethods(type: KClass<*>): List<KFunction<*>> = type.functions
        .filter { it.findAnnotation<PostConstruct>() != null || it.findAnnotation<PreDestroy>() != null }
        .sortedBy(::functionSignature)

    private fun buildTypeIndex(definitions: Collection<BeanDefinition>): Map<KClass<*>, Set<String>> {
        val result = mutableMapOf<KClass<*>, MutableSet<String>>()
        definitions.forEach { definition ->
            indexedTypes(definition.type).forEach { type ->
                result.getOrPut(type) { sortedSetOf() }.add(definition.name)
            }
        }
        return result
    }

    private fun indexedTypes(type: KClass<*>): Set<KClass<*>> {
        val result = linkedSetOf(type)
        fun visit(current: Class<*>) {
            current.interfaces.sortedBy { it.name }.forEach { if (result.add(it.kotlin)) visit(it) }
            current.superclass?.takeUnless { it == Any::class.java || it == Object::class.java }
                ?.let { if (result.add(it.kotlin)) visit(it) }
        }
        visit(type.java)
        return result
    }

    private fun findCycles(names: Set<String>, edges: List<DependencyEdge>): List<VerificationIssue.CircularDependency> {
        val adjacency = edges.filter { it.resolvedProvider != null }
            .groupBy { it.requestingBean }
            .mapValues { (_, value) -> value.mapNotNull { it.resolvedProvider }.distinct().sorted() }
        val state = mutableMapOf<String, Int>()
        val stack = mutableListOf<String>()
        val cycles = sortedSetOf<String>()
        fun visit(node: String) {
            state[node] = 1
            stack += node
            adjacency[node].orEmpty().forEach { dependency ->
                when (state[dependency] ?: 0) {
                    0 -> visit(dependency)
                    1 -> {
                        val path = stack.subList(stack.indexOf(dependency), stack.size).toList()
                        val normalized = normalizeCycle(path)
                        cycles += normalized.joinToString("\u0000")
                    }
                }
            }
            stack.removeAt(stack.lastIndex)
            state[node] = 2
        }
        names.sorted().forEach { if ((state[it] ?: 0) == 0) visit(it) }
        return cycles.map { encoded ->
            val open = encoded.split("\u0000")
            VerificationIssue.CircularDependency(open + open.first())
        }
    }

    private fun normalizeCycle(cycle: List<String>): List<String> {
        val start = cycle.indices.minBy { cycle[it] }
        return cycle.indices.map { cycle[(start + it) % cycle.size] }
    }

    private fun topologicalOrder(names: Set<String>, edges: List<DependencyEdge>): List<String> {
        val dependencies = names.associateWith { sortedSetOf<String>() }.toMutableMap()
        edges.forEach { edge -> edge.resolvedProvider?.let { dependencies.getValue(edge.requestingBean).add(it) } }
        val remaining = dependencies.mapValues { (_, value) -> value.toMutableSet() }.toMutableMap()
        val result = mutableListOf<String>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.filterValues { it.isEmpty() }.keys.sorted()
            if (ready.isEmpty()) return emptyList()
            ready.forEach { name ->
                result += name
                remaining.remove(name)
                remaining.values.forEach { it.remove(name) }
            }
        }
        return result
    }

    private fun canonicalGraph(graph: DependencyGraph): String = buildString {
        append("nexus-dependency-graph-v1\n")
        graph.nodes.forEach { node ->
            append("node|").append(node.name).append('|').append(node.type).append('|').append(node.scope)
                .append('|').append(node.constructor ?: "-").append('|').append(node.origin.name)
                .append('|').append(node.qualifiers.joinToString(","))
                .append('|').append(node.lifecycleMethods.joinToString(",")).append('\n')
        }
        graph.edges.forEach { edge ->
            append("edge|").append(edge.requestingBean).append('|').append(edge.parameter).append('|')
                .append(edge.requestedType).append('|').append(edge.qualifier ?: "-").append('|')
                .append(edge.resolvedProvider ?: "-").append('\n')
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun functionSignature(function: KFunction<*>): String =
        function.name + function.parameters.filter { it.kind == KParameter.Kind.VALUE }
            .joinToString(prefix = "(", postfix = ")") { it.type.toString() }
}

private fun typeName(type: KClass<*>): String = type.qualifiedName ?: type.java.name
private fun constructorSignature(constructor: KFunction<*>): String =
    constructor.parameters.joinToString(prefix = "(", postfix = ")") { it.type.toString() }

private fun VerificationIssue.toJson(): String = buildString {
    append("{\"code\":").append(toJsonString(code))
    append(",\"beanName\":").append(toJsonString(beanName))
    append(",\"detail\":").append(toJsonString(detail))
    append(",\"evidence\":{")
    when (this@toJson) {
        is VerificationIssue.MissingDependency -> {
            append("\"parameter\":").append(toJsonString(parameter))
            append(",\"requestedType\":").append(toJsonString(requestedType))
            append(",\"qualifier\":").append(qualifier?.let(::toJsonString) ?: "null")
        }
        is VerificationIssue.AmbiguousDependency -> {
            append("\"parameter\":").append(toJsonString(parameter))
            append(",\"requestedType\":").append(toJsonString(requestedType))
            append(",\"providers\":").append(toJsonStrings(providers))
        }
        is VerificationIssue.CircularDependency -> append("\"cycle\":").append(toJsonStrings(cycle))
        is VerificationIssue.DuplicateBeanName -> append("\"types\":").append(toJsonStrings(types))
        is VerificationIssue.DuplicateTypeIndexEntry ->
            append("\"indexedType\":").append(toJsonString(indexedType))
        is VerificationIssue.NoUsableConstructor ->
            append("\"componentType\":").append(toJsonString(componentType))
        is VerificationIssue.UnresolvableParameterType -> {
            append("\"parameter\":").append(toJsonString(parameter))
            append(",\"renderedType\":").append(toJsonString(renderedType))
        }
        is VerificationIssue.InvalidLifecycleMethod -> {
            append("\"method\":").append(toJsonString(method))
            append(",\"reason\":").append(toJsonString(reason))
        }
        is VerificationIssue.ContradictoryQualifier -> {
            append("\"parameter\":").append(toJsonString(parameter))
            append(",\"qualifier\":").append(toJsonString(qualifier))
            append(",\"requestedType\":").append(toJsonString(requestedType))
            append(",\"providerType\":").append(toJsonString(providerType))
        }
    }
    append("}}")
}

private fun toJsonString(value: String): String = "\"${value.escapeJson()}\""
private fun toJsonStrings(values: List<String>): String =
    values.joinToString(separator = ",", prefix = "[", postfix = "]", transform = ::toJsonString)
private fun String.escapeJson(): String = replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

/** Thrown by one-call creation when a candidate graph is rejected. */
class ContextVerificationException(val receipt: VerificationReceipt) : NexusException(
    "Nexus context verification rejected ${receipt.issues.size} issue(s): " +
        receipt.issues.joinToString("; ") { it.detail }
)

/** Thrown when activation fails after verification, with deterministic cleanup details. */
class ContextActivationException(
    message: String,
    cause: Throwable,
    val receipt: VerificationReceipt,
    val constructedComponents: List<String>,
    val activatedComponents: List<String>,
    val failedComponents: List<String>,
    val cleanedComponents: List<String>
) : NexusException(message, cause)
