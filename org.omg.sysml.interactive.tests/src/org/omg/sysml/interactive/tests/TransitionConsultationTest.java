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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.ecore.resource.Resource;
import org.junit.Test;
import org.omg.sysml.lang.sysml.BindingConnector;
import org.omg.sysml.lang.sysml.Feature;
import org.omg.sysml.lang.sysml.FeatureDirectionKind;
import org.omg.sysml.lang.sysml.Membership;
import org.omg.sysml.lang.sysml.ParameterMembership;
import org.omg.sysml.lang.sysml.Relationship;
import org.omg.sysml.lang.sysml.StateUsage;
import org.omg.sysml.lang.sysml.SysMLFactory;
import org.omg.sysml.lang.sysml.TransitionUsage;
import org.omg.sysml.lang.sysml.Type;
import org.omg.sysml.util.ElementUtil;
import org.omg.sysml.util.FeatureUtil;
import org.omg.sysml.util.TypeUtil;
import org.omg.sysml.util.UsageUtil;

/** Characterizes transition source selection, prepared structure and binding endpoints. */
public class TransitionConsultationTest extends AbstractImplicitSpecializationTest {

	/**
	 * A cached explicit transition retains its source, link and both semantic
	 * bindings through repeated queries, cache clearing and materialization.
	 */
	@Test
	public void explicitTransitionPreservesBindingsAndStructure() throws Exception {
		Resource resource = parse("explicitTransition.sysml", """
				state def Machine {
					state off;
					state on;
					transition t first off if true then on;
				}
				""", true);
		TransitionUsage transition = findByName(resource, "t", TransitionUsage.class);
		StateUsage off = findByName(resource, "off", StateUsage.class);
		StateUsage on = findByName(resource, "on", StateUsage.class);
		// getSource enters addAdditionalMembers and can create the link and binding candidates.
		assertSame(off, transition.getSource());
		assertSame(on, transition.getTarget());
		Feature link = UsageUtil.getTransitionLinkFeatureOf(transition);
		assertNotNull(link);
		Feature input = TypeUtil.getOwnedParametersOf(transition).get(0);
		List<BindingConnector> bindings = bindingsOf(transition);
		assertBinding(bindings, off, input);
		assertBinding(bindings, transition.getSuccession(), link);
		assertTrue(FeatureUtil.getRedefinedFeaturesWithComputedOf(link).stream().anyMatch(feature ->
				"TransitionPerformances::TransitionPerformance::transitionLink".equals(feature.getQualifiedName())));
		List<Relationship> relationships = List.copyOf(transition.getOwnedRelationship());

		// Read-order changes and clearing derived caches must preserve the prepared identities.
		for (int i = 0; i < 2; i++) {
			ElementUtil.clearCachesOf(transition);
			transition.getFeatureMembership();
			assertSame(off, transition.getSource());
			assertSame(link, UsageUtil.getTransitionLinkFeatureOf(transition));
			assertEquals(relationships, transition.getOwnedRelationship());
			assertEquals(bindings, bindingsOf(transition));
		}
		ElementUtil.transformAll(resource, true);
		ElementUtil.transformAll(resource, true);
		assertSame(link, UsageUtil.getTransitionLinkFeatureOf(transition));
		List<BindingConnector> owned = transition.getOwnedMember().stream()
				.filter(BindingConnector.class::isInstance).map(BindingConnector.class::cast).toList();
		assertBinding(owned, off, input);
		assertBinding(owned, transition.getSuccession(), link);
	}

	/**
	 * Consecutive shorthand transitions use the same preceding state, skipping the
	 * preceding transition. A self-loop terminates and retains its selected source.
	 */
	@Test
	public void shorthandTransitionsSkipEachOtherAndSupportSelfLoops() throws Exception {
		Resource resource = parse("shorthandTransitions.sysml", """
				state def Machine {
					state off;
					if true then on;
					if false then off;
					state on;
				}
				""");
		Type owner = findByName(resource, "Machine", Type.class);
		StateUsage off = findByName(resource, "off", StateUsage.class);
		StateUsage on = findByName(resource, "on", StateUsage.class);
		List<TransitionUsage> transitions = owner.getOwnedFeature().stream()
				.filter(TransitionUsage.class::isInstance).map(TransitionUsage.class::cast).toList();
		assertEquals(2, transitions.size());
		// Consult the second transition first to exercise reverse consultation order.
		assertSame(off, transitions.get(1).getSource());
		assertSame(off, transitions.get(1).getTarget());
		assertSame(off, transitions.get(0).getSource());
		assertSame(on, transitions.get(0).getTarget());
		for (TransitionUsage transition : transitions) {
			Membership sourceMembership = transition.getOwnedMembership().get(0);
			assertSame(off, sourceMembership.getMemberElement());
			List<Relationship> relationships = List.copyOf(transition.getOwnedRelationship());
			transition.getFeatureMembership();
			ElementUtil.clearCachesOf(transition);
			assertSame(off, transition.getSource());
			assertSame(sourceMembership, transition.getOwnedMembership().get(0));
			assertEquals(relationships, transition.getOwnedRelationship());
		}
	}

	/**
	 * A detached transition with an input but no source or succession supports
	 * reentrant source queries. Attaching it after a state fills the existing
	 * source membership instead of replacing the membership or the input.
	 */
	@Test
	public void incompleteTransitionRecoversAfterAttachment() throws Exception {
		Resource resource = parse("attachedTransition.sysml", """
				state def Machine { state off; }
				""");
		Type owner = findByName(resource, "Machine", Type.class);
		StateUsage off = findByName(resource, "off", StateUsage.class);
		// The detached, incomplete construction state cannot be expressed by valid transition syntax.
		TransitionUsage transition = SysMLFactory.eINSTANCE.createTransitionUsage();
		Feature input = SysMLFactory.eINSTANCE.createReferenceUsage();
		input.setDirection(FeatureDirectionKind.IN);
		ParameterMembership parameter = SysMLFactory.eINSTANCE.createParameterMembership();
		parameter.setOwnedMemberParameter(input);
		transition.getOwnedRelationship().add(parameter);
		assertNull(transition.getSource());
		Membership sourceMembership = transition.getOwnedMembership().get(0);
		for (int i = 0; i < 3; i++) {
			assertNull(transition.getSource());
			assertNull(transition.getSuccession());
			assertNull(UsageUtil.getTransitionLinkFeatureOf(transition));
			assertEquals(List.of(sourceMembership, parameter), transition.getOwnedRelationship());
		}
		assertTrue(bindingsOf(transition).isEmpty());
		// Once ownership is established, the previously absent lexical source becomes available.
		TypeUtil.addOwnedFeatureTo(owner, transition);
		assertSame(off, transition.getSource());
		assertSame(sourceMembership, transition.getOwnedMembership().get(0));
		assertSame(input, parameter.getOwnedMemberParameter());
		assertBinding(bindingsOf(transition), off, input);
	}

	/**
	 * Editing an explicit source changes the public source while retaining the
	 * state-transition specialization, membership and prepared link. Connector
	 * refresh is a separate lifecycle concern, not asserted by this scenario.
	 */
	@Test
	public void explicitSourceEditsPreservePreparedElements() throws Exception {
		Resource resource = parse("editedTransition.sysml", """
				state def Machine {
					state off;
					state on;
					transition t first off if true then on;
				}
				""");
		TransitionUsage transition = findByName(resource, "t", TransitionUsage.class);
		StateUsage off = findByName(resource, "off", StateUsage.class);
		StateUsage on = findByName(resource, "on", StateUsage.class);
		assertSame(off, transition.getSource());
		Feature link = UsageUtil.getTransitionLinkFeatureOf(transition);
		Membership sourceMembership = transition.getOwnedMembership().get(0);
		List<Relationship> relationships = List.copyOf(transition.getOwnedRelationship());
		for (StateUsage source : List.of(on, off)) {
			// Equivalent to first on, then first off, without replacing the transition.
			sourceMembership.setMemberElement(source);
			ElementUtil.clearCachesOf(transition);
			assertSame(source, transition.getSource());
			assertSame(link, UsageUtil.getTransitionLinkFeatureOf(transition));
			assertEquals(relationships, transition.getOwnedRelationship());
			assertTrue(TypeUtil.getGeneralTypesOf(transition).stream().anyMatch(type ->
					"States::StateAction::stateTransitions".equals(type.getQualifiedName())));
			assertFalse(ElementUtil.isTransformed(transition));
		}
	}

	/**
	 * A producer-supplied link is retained with its name when the source is queried;
	 * consultation must not append another reference usage or replace its membership.
	 */
	@Test
	public void existingTransitionLinkIsPreserved() throws Exception {
		Resource resource = parse("suppliedTransitionLink.sysml", """
				state def Machine {
					state off;
					state on;
					transition t first off then on;
				}
				""");
		TransitionUsage transition = findByName(resource, "t", TransitionUsage.class);
		// Supply the link before the first semantic consultation, as a programmatic producer could.
		Feature supplied = SysMLFactory.eINSTANCE.createReferenceUsage();
		supplied.setDeclaredName("providedLink");
		TypeUtil.addOwnedFeatureTo(transition, supplied);
		List<Relationship> relationships = List.copyOf(transition.getOwnedRelationship());
		assertSame(findByName(resource, "off", StateUsage.class), transition.getSource());
		transition.getFeatureMembership();
		assertSame(supplied, UsageUtil.getTransitionLinkFeatureOf(transition));
		assertEquals("providedLink", supplied.getDeclaredName());
		assertEquals(relationships, transition.getOwnedRelationship());
	}

	private List<BindingConnector> bindingsOf(TransitionUsage transition) {
		List<BindingConnector> bindings = new ArrayList<>();
		TypeUtil.forEachImplicitBindingConnectorOf(transition, (binding, kind) -> bindings.add(binding));
		return bindings;
	}

	private void assertBinding(List<BindingConnector> bindings, Feature source, Feature target) {
		assertEquals("Expected exactly one binding for the selected endpoints", 1,
				bindings.stream().filter(binding -> binding.getRelatedFeature().contains(source)
						&& binding.getRelatedFeature().contains(target)).count());
	}
}
