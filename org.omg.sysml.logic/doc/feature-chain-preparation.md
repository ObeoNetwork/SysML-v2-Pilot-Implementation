# Feature-chain source-target preparation

## Purpose

ST6RI-981 separates structural preparation of feature-chain expressions from
semantic consultation, following expression result preparation in ST6RI-979.
Derived queries and cache computation do not create the owned source-target
feature. Typing and redefinitions remain computed from the current expression
and its target. Pilot caching and SysON's uncached editable models are supported.

## Preparation and consultation

For a textual expression such as `v.n`, the parser constructs a
`FeatureChainExpression` with an input parameter whose value refers to `v`, a
membership referring to `n`, and an output result prepared by ST6RI-979.
`FeatureChainExpressionParserPostProcessor` first invokes inherited expression
preparation, preserving the output result, then calls
`ExpressionUtil.prepareSourceTargetFeature(FeatureChainExpression)`.

The helper inspects the expression's owned feature memberships in order and
selects the first feature with stored direction `IN`. It inspects that feature's
owned feature memberships, preserving existing nested features and their names.
If none exists, it creates one nested Feature and its FeatureMembership. The
nested feature has the empty declared name to suppress effective naming.

These operations inspect only local containment and stored direction. They do
not follow the source value, target membership, inferred types or specialization
targets. The owned-member accessor used by the helper reads the membership's
contained elements. Repeated preparation preserves element identities. An
expression without an input is left unchanged and can be prepared after the
input is installed. Preparation does not install or invalidate caches.

`FeatureChainExpressionAdapter` has no structural preparation override.
`sourceTargetFeature()` returns the first owned feature found beneath an owned
input feature. Other semantic queries use the prepared structure and do not
recreate a removed source target.

The specialization rules use the nested feature to redefine the expression's
`targetFeature` and `ControlFunctions::'.'::source::target`. The result specializes
the chain of the source parameter and the source-target feature. These rules are
implemented by `featureChainExpressionTargetRedefinition` and
`featureChainExpressionResultSpecialization` in `KerMLExpressionRules`; their
Javadoc cites KerML section 8.3.4.8.4 and section 8.4.4.1, Table 11, note 5.
Their computation and recursion handling are unchanged.

Two different structures must not be confused: `a.next.next` may represent a
single expression whose target is a feature chain, whereas `(a.next).next`
explicitly nests two FeatureChainExpressions.

```mermaid
flowchart LR
    Parser[Parser: operand construction] --> Post[Feature-chain expression post-processing]
    Post --> Prepare[ExpressionUtil.prepareSourceTargetFeature]
    Editor[Editor creation or XMI import caller] --> Prepare
    Prepare --> Structure[Input parameter owns source-target Feature]
    Structure --> Query[Typing and specialization queries]
    Edit[Change target membership] --> Query
```

## Producer and editor responsibilities

Programmatic builders and XMI consumers must call the helper after constructing
or loading a chain lacking its nested source target, before semantic consultation.
Loading XMI alone does not invoke parser post-processors. No automatic preparation
is added to the generic XMI loader or generated EMF editing commands. A serialized
chain that already contains its source target needs no repair.

Changing the target membership preserves ownership and identity of the prepared
feature. The regression tests exercise a missing target followed by Boolean and
Integer targets, without transformation or another preparation call. Semantic
results follow those edits. Model specialization caching is disabled in these
editing tests; legacy derived caches are explicitly cleared. This is not a claim
of automatic dependency invalidation.

Replacing the source input requires preparing the replacement explicitly. The
old nested feature remains owned by the old input. Removing the source target
also requires explicit preparation to restore it; queries do not repair incomplete
editor states. Callers remain responsible for their existing cache invalidation
policy when modifying cached models.

## Ordering and limitations

Preparation selects the first input even if an output parameter was inserted
before it. The earlier consultation-time creator selected the first parameter
regardless of direction and could incorrectly attach the nested feature to an
output in programmatically constructed models. This ordering difference is
intentional and covered by a regression test.

For an incomplete model with several inputs, preparation handles the first input
and leaves subsequent inputs unchanged, including their nested features. The
existing reader still searches across inputs: before explicit preparation, it
can return a nested feature of a later input if the first input has none. No
change to that query's semantics is included here.

Parser preparation introduces the nested feature before linking. Tests cover
unresolved references, repeated parser callbacks, nested chains and cyclic source
subsettings. They do not establish performance equivalence; benchmark execution
remains deferred at the user's request.

## Regression coverage

`FeatureChainConsultationTest` covers:

- Cached textual chain: source-target and result identity, target and library
  redefinitions, synthesized result chain, typing, argument order and repeated
  materialization.
- Uncached target edits: missing target, replacement and restoration, with stable
  containment and no semantic transformation.
- Nested expressions with cyclic source subsettings and recursive structure typing.
- Incomplete programmatic construction followed by explicit preparation.
- Preservation of an explicitly supplied nested feature.
- XMI loading without a source-target feature, then explicit preparation.
- Parser preparation before reference resolution and idempotent callbacks.
- Removal of the nested feature followed by cold semantic queries that do not
  recreate it.
- Output-before-input construction, multiple inputs and replacement of the source.

Existing `VirtualContainerTest` and
`ContextualFeatureChainConnectorFlowLoopImplicitSpecializationTest` complement
these tests for detached result chains and implicit redefinitions.

The baseline commit passed root `mvn clean verify` in 10 min 12 s on 2026-10-12
(1669 tests reported, no failures or errors, 22 skipped). The implementation passed
root `mvn clean verify` in 10 min 02 s on the same date (1672 tests reported,
no failures or errors, 22 skipped), including all nine feature-chain tests.
Both bundled JARs were rebuilt. No benchmark was run.

Dedicated post-processor validation on 2026-10-23: root `mvn clean verify`
succeeded in 10 min 39 s (1672 tests reported, no failures or errors, 22 skipped),
including all nine feature-chain tests and 14 expression-result tests.
Both bundled JARs match the rebuilt artifacts. No benchmark was run.
