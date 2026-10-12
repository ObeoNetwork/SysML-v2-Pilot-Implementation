/*******************************************************************************
 * SysML 2 Pilot Implementation
 * Copyright (c) 2026 Obeo
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.omg.sysml.interactive.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl;
import org.eclipse.xtext.EcoreUtil2;
import org.junit.Test;
import org.omg.sysml.lang.sysml.Element;
import org.omg.sysml.lang.sysml.Expression;
import org.omg.sysml.lang.sysml.Feature;
import org.omg.sysml.lang.sysml.FeatureChainExpression;
import org.omg.sysml.lang.sysml.FeatureDirectionKind;
import org.omg.sysml.lang.sysml.Membership;
import org.omg.sysml.lang.sysml.Relationship;
import org.omg.sysml.lang.sysml.SysMLFactory;
import org.omg.sysml.lang.sysml.Type;
import org.omg.sysml.logic.implicit.specialization.api.IImplicitSpecializationCache;
import org.omg.sysml.util.ElementUtil;
import org.omg.sysml.util.FeatureUtil;
import org.omg.sysml.util.TypeUtil;

/** Characterizes feature-chain structure and semantics across consultation and editing. */
public class FeatureChainConsultationTest extends AbstractImplicitSpecializationTest {

	/**
	 * A cached textual chain retains its source target, output and argument order
	 * through queries, cache clearing and repeated materialization.
	 */
	@Test
	public void chainRetainsSourceTargetAndResult() throws Exception {
		Resource resource = parse("chainLifecycle.kerml", """
				package Chain {
					struct V { feature n : ScalarValues::Integer; }
					feature v : V;
					feature selected = v.n;
				}
				""", true);
		FeatureChainExpression chain = findSingle(resource, FeatureChainExpression.class);
		Feature target = findByName(resource, "n", Feature.class);
		// Resolution and result lookup can create the nested source target in the current implementation.
		EcoreUtil2.resolveLazyCrossReferences(resource, null);
		Feature result = chain.getResult();
		Feature source = sourceParameter(chain);
		Feature sourceTarget = chain.sourceTargetFeature();
		assertNotNull(sourceTarget);
		assertEquals("", sourceTarget.getDeclaredName());
		assertSame(source, sourceTarget.getOwningType());
		assertEquals(List.of(sourceTarget), source.getOwnedFeature());
		List<Expression> arguments = List.copyOf(chain.getArgument());
		assertEquals(1, arguments.size());
		assertEquals(target.getType(), result.getType());
		assertTrue(FeatureUtil.getRedefinedFeaturesWithComputedOf(sourceTarget).contains(target));
		assertTrue(FeatureUtil.getRedefinedFeaturesWithComputedOf(sourceTarget).stream()
				.anyMatch(feature -> "ControlFunctions::'.'::source::target".equals(feature.getQualifiedName())));
		assertTrue(TypeUtil.getGeneralTypesOf(result).stream().anyMatch(type -> type instanceof Feature feature
				&& feature.getChainingFeature().equals(List.of(source, sourceTarget))));

		// Cold derived queries and transformation must preserve structural identities and argument positions.
		clearDerivedCaches(resource);
		assertSame(result, chain.getResult());
		assertSame(sourceTarget, chain.sourceTargetFeature());
		ElementUtil.transformAll(resource, true);
		ElementUtil.transformAll(resource, true);
		assertSame(result, chain.getResult());
		assertSame(sourceTarget, chain.sourceTargetFeature());
		assertEquals(arguments, chain.getArgument());
		assertEquals(List.of(sourceTarget), source.getOwnedFeature());
		assertEquals(target.getType(), result.getType());
	}

	/**
	 * Retargeting a prepared chain A -> missing -> B -> A changes its redefinitions
	 * and result type without replacing its nested feature or transforming the model.
	 */
	@Test
	public void targetEditsPreservePreparedStructure() throws Exception {
		Resource resource = parse("editedChain.kerml", """
				package Edited {
					struct V {
						feature number : ScalarValues::Integer;
						feature flag : ScalarValues::Boolean;
					}
					feature v : V;
					feature selected = v.number;
				}
				""");
		FeatureChainExpression chain = findSingle(resource, FeatureChainExpression.class);
		Feature number = findByName(resource, "number", Feature.class);
		Feature flag = findByName(resource, "flag", Feature.class);
		EcoreUtil2.resolveLazyCrossReferences(resource, null);
		Feature result = chain.getResult();
		Feature sourceTarget = chain.sourceTargetFeature();
		assertNotNull(sourceTarget);
		Membership reference = chain.getOwnedMembership().stream()
				.filter(member -> member.getMemberElement() == number).findFirst().orElseThrow();
		List<Relationship> relationships = List.copyOf(sourceParameter(chain).getOwnedRelationship());

		// An absent target is an intermediate editor state, not a request to rebuild the source target.
		reference.setMemberElement(null);
		clearDerivedCaches(resource);
		assertNull(chain.getTargetFeature());
		assertFalse(FeatureUtil.getRedefinedFeaturesWithComputedOf(sourceTarget).contains(number));
		assertSame(sourceTarget, chain.sourceTargetFeature());
		for (Feature target : List.of(flag, number)) {
			// Equivalent texts: v.flag, then v.number, retaining the expression and its source parameter.
			reference.setMemberElement(target);
			clearDerivedCaches(resource);
			List<Feature> redefined = FeatureUtil.getRedefinedFeaturesWithComputedOf(sourceTarget);
			assertEquals(List.of(target), redefined.stream().filter(f -> f == number || f == flag).toList());
			assertEquals(target.getType(), result.getType());
			assertSame(result, chain.getResult());
			assertSame(sourceTarget, chain.sourceTargetFeature());
			assertEquals(relationships, sourceParameter(chain).getOwnedRelationship());
			assertNull(IImplicitSpecializationCache.find(sourceTarget));
			assertFalse(ElementUtil.isTransformed(chain));
		}
	}

	/**
	 * Nested chains over cyclic source subsettings terminate and keep distinct
	 * source targets, while their results retain the recursive structure's type.
	 */
	@Test
	public void nestedChainsWithCyclicSourcesRetainDistinctTargets() throws Exception {
		Resource resource = parse("cyclicChain.kerml", """
				package Cyclic {
					struct Node { feature next : Node; }
					feature a : Node :> b;
					feature b : Node :> a;
					feature selected = (a.next).next;
				}
				""");
		Feature selected = findByName(resource, "selected", Feature.class);
		FeatureChainExpression outer = (FeatureChainExpression) FeatureUtil.getValuationFor(selected).getValue();
		EcoreUtil2.resolveLazyCrossReferences(resource, null);
		FeatureChainExpression inner = (FeatureChainExpression) outer.getArgument().get(0);
		Type node = findByName(resource, "Node", Type.class);
		// Query the outer result first to exercise nested semantic consultation.
		assertEquals(List.of(node), outer.getResult().getType());
		assertEquals(List.of(node), inner.getResult().getType());
		Feature outerTarget = outer.sourceTargetFeature();
		Feature innerTarget = inner.sourceTargetFeature();
		assertNotNull(outerTarget);
		assertNotNull(innerTarget);
		assertNotSame(innerTarget, outerTarget);
		clearDerivedCaches(resource);
		assertEquals(List.of(node), outer.getResult().getType());
		assertSame(outerTarget, outer.sourceTargetFeature());
		assertSame(innerTarget, inner.sourceTargetFeature());
	}

	/**
	 * An incomplete programmatic chain tolerates an absent parameter; once an input
	 * exists, repeated feature consultation supplies exactly one nested source target.
	 */
	@Test
	public void programmaticChainSupportsDeferredSourceCreation() {
		// No textual equivalent for this intermediate construction state: neither input nor output exists yet.
		FeatureChainExpression chain = SysMLFactory.eINSTANCE.createFeatureChainExpression();
		assertNull(chain.sourceTargetFeature());
		chain.getFeature();
		assertTrue(chain.getOwnedRelationship().isEmpty());
		Feature source = TypeUtil.addOwnedParameterTo(chain, SysMLFactory.eINSTANCE.createLiteralInteger())
				.getOwnedMemberParameter();
		TypeUtil.addResultParameterTo(chain);
		ElementUtil.clearCachesOf(chain);
		// Match the parser's completed parameter order before consulting the chain.
		chain.getFeature();
		Feature sourceTarget = chain.sourceTargetFeature();
		assertNotNull(sourceTarget);
		assertEquals("", sourceTarget.getDeclaredName());
		chain.getFeatureMembership();
		assertSame(sourceTarget, chain.sourceTargetFeature());
		assertEquals(List.of(sourceTarget), source.getOwnedFeature());
	}

	/**
	 * A caller-supplied nested feature is retained, including its name, rather than
	 * being replaced by an anonymous source target during consultation.
	 */
	@Test
	public void explicitNestedSourceFeatureIsPreserved() {
		// Programmatically supply the nested feature that the textual syntax normally leaves implicit.
		FeatureChainExpression chain = SysMLFactory.eINSTANCE.createFeatureChainExpression();
		Feature source = TypeUtil.addOwnedParameterTo(chain, SysMLFactory.eINSTANCE.createLiteralInteger())
				.getOwnedMemberParameter();
		Feature explicit = SysMLFactory.eINSTANCE.createFeature();
		explicit.setDeclaredName("explicitTarget");
		TypeUtil.addOwnedFeatureTo(source, explicit);
		TypeUtil.addResultParameterTo(chain);
		chain.getFeature();
		chain.getResult();
		assertSame(explicit, chain.sourceTargetFeature());
		assertEquals("explicitTarget", explicit.getDeclaredName());
		assertEquals(List.of(explicit), source.getOwnedFeature());
	}

	/**
	 * An XMI chain lacking the nested feature acquires a source target through the
	 * current consultation path and retains its target identity and inferred type.
	 */
	@Test
	public void xmiChainProvidesSourceTargetWithoutParserCallback() throws Exception {
		Resource parsed = parse("xmiChain.kerml", """
				package XmiChain {
					struct V { feature n : ScalarValues::Integer; }
					feature v : V;
					feature selected = v.n;
				}
				""");
		EcoreUtil2.resolveLazyCrossReferences(parsed, null);
		FeatureChainExpression original = findSingle(parsed, FeatureChainExpression.class);
		Feature sourceTarget = original.sourceTargetFeature();
		assertNotNull(sourceTarget);
		// Serialize a deliberately unprepared nested structure while preserving input, output and references.
		sourceParameter(original).getOwnedRelationship().remove(sourceTarget.getOwningRelationship());
		Resource serialized = new XMIResourceImpl(URI.createURI("memory:/chainSerialized.xmi"));
		serialized.getContents().addAll(parsed.getContents());
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		serialized.save(bytes, Map.of());
		Resource loaded = new XMIResourceImpl(URI.createURI("memory:/chainLoaded.xmi"));
		try {
			parsed.getResourceSet().getResources().add(loaded);
			loaded.load(new ByteArrayInputStream(bytes.toByteArray()), Map.of());
			FeatureChainExpression chain = findSingle(loaded, FeatureChainExpression.class);
			assertNull(chain.sourceTargetFeature());
			Feature result = chain.getResult();
			Feature restored = chain.sourceTargetFeature();
			assertNotNull(restored);
			Feature target = findByName(loaded, "n", Feature.class);
			assertSame(target, chain.getTargetFeature());
			assertTrue(FeatureUtil.getRedefinedFeaturesWithComputedOf(restored).contains(target));
			assertEquals(target.getType(), result.getType());
			assertSame(restored, chain.sourceTargetFeature());
		} finally {
			loaded.unload();
			parsed.getResourceSet().getResources().remove(loaded);
		}
	}

	private Feature sourceParameter(FeatureChainExpression chain) {
		return chain.getOwnedFeature().stream().filter(feature -> feature.getDirection() == FeatureDirectionKind.IN)
				.findFirst().orElseThrow();
	}

	private void clearDerivedCaches(Resource resource) {
		for (var contents = resource.getAllContents(); contents.hasNext();) {
			if (contents.next() instanceof Element element) {
				ElementUtil.clearCachesOf(element);
			}
		}
	}
}
