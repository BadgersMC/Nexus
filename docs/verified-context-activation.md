# Verified context activation

This change adds a strict verification boundary to `nexus-core`.

## Candidate and active contexts

`NexusContext.prepare(...)` scans component definitions, combines them with configuration and external definitions, constructs a candidate dependency graph, and verifies that graph. It does not invoke scanned component constructors or lifecycle callbacks. A `ContextCandidate` exposes its immutable `graph` and `receipt` before activation.

`candidate.activate()` is permitted only for an accepted receipt and can run once. `NexusContext.create(...)` remains compatible and performs prepare, verify, and activate as one operation. Rejection produces one `ContextVerificationException` carrying the complete receipt.

## Verification lifecycle

The verifier records component names, concrete types, scopes, selected primary constructors, constructor parameters and qualifiers, lifecycle methods, origins, and resolved providers. It reports typed issues for missing and ambiguous dependencies, constructor cycles, incompatible duplicate names, duplicate type-index entries, unusable constructors, unresolvable parameter types, unsafe lifecycle methods, and contradictory qualifiers.

Cycle issues include the complete closed path. Verification traversals cannot recurse through bean factories, so a cycle is rejected rather than becoming a stack overflow.

## Deterministic receipts

`VerificationReceipt` has an explicit schema version, accepted/rejected status, component and dependency counts, typed issues, activation order, shutdown order, and a SHA-256 graph hash. Nodes, edges, lifecycle signatures, issues, and orders are sorted by stable semantic keys. The hash comes from a versioned canonical graph representation, never object identity or map/reflection iteration order.

`VerificationReceipt.toJson()` emits stable JSON without requiring another runtime dependency. Each typed issue includes a structured `evidence` object (for example cycle paths, parameters, requested types, providers, qualifiers, lifecycle methods, and reasons) alongside its human-readable detail. Receipt fields remain directly inspectable in Kotlin. Schema version 2 identifies this structured issue format.

## Activation, rollback, and shutdown

Accepted singleton components activate in dependency-first topological order. Prototype definitions are fully verified but remain lazy. If construction or `@PostConstruct` fails, activation stops. A singleton that reached construction but failed `@PostConstruct` is removed from the registry and receives best-effort `@PreDestroy`; previously installed external/config instances and activated scanned components are then cleaned in reverse dependency order. Runtime coroutine resources close, and `ContextActivationException` separately reports constructed, activated, failed, and successfully cleaned component names. A failed context is never returned.

Normal `close()` is idempotent. Dependants are destroyed before their providers, and registry removal ensures each singleton is destroyed at most once. The computed order is included in the verification receipt.

## Compatibility and limitations

Singleton and prototype scopes, constructor injection, qualifiers, external beans, configuration beans, coroutine infrastructure, suspend lifecycle methods, interface/superclass lookup, manual-only contexts, and `NexusContext.close()` remain supported.

Manual definitions registered after `NexusContext.create()` cannot be part of an earlier scanned candidate receipt; their factories therefore retain the legacy lazy behavior. External instances and configuration values necessarily exist before candidate verification, but scanned user components are never instantiated before acceptance. Factory bodies are opaque and cannot be statically analyzed; failures inside an accepted factory are handled by activation rollback. Receipt schema evolution will require a new schema and canonical-graph version.
