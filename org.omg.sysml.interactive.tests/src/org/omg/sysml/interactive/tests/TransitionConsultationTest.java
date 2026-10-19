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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.emf.common.notify.Notification;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EReference;
import org.eclipse.emf.ecore.InternalEObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EContentAdapter;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl;
import org.eclipse.xtext.resource.XtextResource;
import org.junit.Test;
import org.omg.sysml.adapter.ElementAdapterFactory;
import org.omg.sysml.adapter.TransitionUsageAdapter;
import org.omg.sysml.lang.sysml.BindingConnector;
import org.omg.sysml.lang.sysml.Feature;
import org.omg.sysml.lang.sysml.FeatureDirectionKind;
import org.omg.sysml.lang.sysml.Membership;
import org.omg.sysml.lang.sysml.ParameterMembership;
import org.omg.sysml.lang.sysml.OwningMembership;
import org.omg.sysml.lang.sysml.Succession;
import org.omg.sysml.lang.sysml.Relationship;
import org.omg.sysml.lang.sysml.StateUsage;
import org.omg.sysml.lang.sysml.SysMLPackage;
import org.omg.sysml.lang.sysml.SysMLFactory;
import org.omg.sysml.lang.sysml.TransitionUsage;
import org.omg.sysml.lang.sysml.Type;
import org.omg.sysml.util.ElementUtil;
import org.omg.sysml.util.FeatureUtil;
import org.omg.sysml.util.TypeUtil;
import org.omg.sysml.util.TransitionUtil;
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
		// Parsing has prepared the source and link before the first semantic query.
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
	 * read-only source queries. Explicit completion after attachment fills the existing
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
		// A getter cannot prepare a programmatically constructed transition.
		assertNull(transition.getSource());
		assertEquals(List.of(parameter), transition.getOwnedRelationship());
		TransitionUtil.prepareStructure(transition);
		TransitionUtil.completeSource(transition);
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
		assertNull(transition.getSource());
		TransitionUtil.completeSource(transition);
		assertSame(off, transition.getSource());
		assertSame(sourceMembership, transition.getOwnedMembership().get(0));
		assertSame(input, parameter.getOwnedMemberParameter());
		for (int i = 0; i < 3; i++) {
			assertBinding(bindingsOf(transition), off, input);
			assertEquals(1, bindingsOf(transition).size());
		}
	}

	/**
	 * Editing an explicit source changes the public source while retaining the
	 * state-transition specialization, membership and prepared link. Detached bindings follow the new source without
	 * accumulating candidates for the previous source.
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
		bindingsOf(transition);
		for (StateUsage source : List.of(on, off)) {
			// Equivalent to first on, then first off, without replacing the transition.
			sourceMembership.setMemberElement(source);
			ElementUtil.clearCachesOf(transition);
			assertSame(source, transition.getSource());
			List<BindingConnector> bindings = bindingsOf(transition);
			assertEquals(2, bindings.size());
			assertBinding(bindings, source, TypeUtil.getOwnedParametersOf(transition).get(0));
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
		// Replace the parser-created link with a producer-supplied one before consultation.
		Feature parsedLink = UsageUtil.getTransitionLinkFeatureOf(transition);
		transition.getOwnedRelationship().remove(parsedLink.getOwningRelationship());
		Feature supplied = SysMLFactory.eINSTANCE.createReferenceUsage();
		supplied.setDeclaredName("providedLink");
		TypeUtil.addOwnedFeatureTo(transition, supplied);
		TransitionUtil.prepareStructure(transition);
		List<Relationship> relationships = List.copyOf(transition.getOwnedRelationship());
		assertSame(findByName(resource, "off", StateUsage.class), transition.getSource());
		transition.getFeatureMembership();
		assertSame(supplied, UsageUtil.getTransitionLinkFeatureOf(transition));
		assertEquals("providedLink", supplied.getDeclaredName());
		assertBinding(bindingsOf(transition), transition.getSuccession(), supplied);
		assertEquals(relationships, transition.getOwnedRelationship());
	}

	/** Moving an imported shorthand transition preserves its selected endpoints, even after preparation. */
	@Test
	public void movingPreparedTransitionPreservesEndpoints() throws Exception {
		Resource resource = parse("movedTransition.sysml", """
				state def Machine { state off; if true then on; state on; }
				""");
		TransitionUsage transition = findSingle(resource, TransitionUsage.class);
		Type owner = findByName(resource, "Machine", Type.class);
		StateUsage off = findByName(resource, "off", StateUsage.class);
		StateUsage on = findByName(resource, "on", StateUsage.class);
		Membership source = transition.getOwnedMembership().get(0);
		Feature link = UsageUtil.getTransitionLinkFeatureOf(transition);
		// Move the same EMF object after on; unlike a textual move, this must still mean off -> on.
		owner.getOwnedRelationship().move(owner.getOwnedRelationship().size() - 1, transition.getOwningRelationship());
		TransitionUtil.prepareStructure(transition);
		TransitionUtil.completeSource(transition);
		ElementUtil.clearCachesOf(transition);
		assertSame(off, transition.getSource());
		assertSame(on, transition.getTarget());
		assertSame(source, transition.getOwnedMembership().get(0));
		assertSame(link, UsageUtil.getTransitionLinkFeatureOf(transition));
		assertBinding(bindingsOf(transition), off, TypeUtil.getOwnedParametersOf(transition).get(0));
	}

	/** Textual insertion and movement reparse shorthand transitions and prepare their new lexical sources. */
	@Test
	public void incrementalTextEditsPrepareNewSources() throws Exception {
		String text = "state def Machine { state off; if true then on; state on; }";
		XtextResource resource = (XtextResource)parse("textEdits.sysml", text);
		TransitionUsage original = findSingle(resource, TransitionUsage.class);
		assertSame(findByName(resource, "off", StateUsage.class), original.getSource());
		// Inserting a state changes the lexical source without explicitly editing the transition line.
		int offset = text.indexOf("if true");
		String insertion = "state middle; ";
		resource.update(offset, 0, insertion);
		text = text.substring(0, offset) + insertion + text.substring(offset);
		TransitionUsage inserted = findSingle(resource, TransitionUsage.class);
		assertNotSame(original, inserted);
		assertSame(findByName(resource, "middle", StateUsage.class), inserted.getSource());
		// Moving the text after on produces a new self-loop, not the old off -> on link.
		String old = "if true then on; state on;";
		resource.update(text.indexOf(old), old.length(), "state on; if true then on;");
		TransitionUsage moved = findSingle(resource, TransitionUsage.class);
		assertSame(findByName(resource, "on", StateUsage.class), moved.getSource());
		assertSame(moved.getSource(), moved.getTarget());
		assertTrue(resource.getErrors().toString(), resource.getErrors().isEmpty());
	}

	/** Queries and binding reconstruction retain all owned structure, including after adapter removal. */
	@Test
	public void consultationAndAdapterReplacementDoNotMutateContainment() throws Exception {
		Resource resource = parse("pureConsultation.sysml", """
				state def Machine { state off; if true then on; state on; }
				""");
		TransitionUsage transition = findSingle(resource, TransitionUsage.class);
		Feature link = UsageUtil.getTransitionLinkFeatureOf(transition);
		assertNotNull(link);
		List<Notification> mutations = new ArrayList<>();
		EContentAdapter observer = new EContentAdapter() {
			@Override
			public void notifyChanged(Notification notification) {
				super.notifyChanged(notification);
				if (notification.getFeature() instanceof EReference reference && reference.isContainment()
						&& !notification.isTouch() && notification.getEventType() != Notification.RESOLVE) {
					mutations.add(notification);
				}
			}
		};
		resource.eAdapters().add(observer);
		try {
			for (int i = 0; i < 3; i++) {
				// A newly installed adapter must rebuild both bindings although the owned link already exists.
				ElementAdapterFactory.removeAdapter(transition);
				transition.getFeatureMembership();
				assertNotNull(transition.getSource());
				assertNotNull(transition.getTarget());
				assertEquals(2, bindingsOf(transition).size());
				assertSame(link, UsageUtil.getTransitionLinkFeatureOf(transition));
				TransitionUsageAdapter adapter = (TransitionUsageAdapter)ElementAdapterFactory.getAdapter(transition);
				adapter.cleanImplicitBindingConnectors();
				assertEquals(2, bindingsOf(transition).size());
			}
			assertTrue("Semantic reads must not change containment: " + mutations, mutations.isEmpty());
		} finally {
			resource.eAdapters().remove(observer);
		}
	}

	/** Prepared links survive XMI loading and supply both bindings without parser-owned adapter state. */
	@Test
	public void xmiRoundTripPreservesPreparedLinks() throws Exception {
		Resource resource = parse("transitionRoundTrip.sysml", """
				state def Machine { state off; if true then on; state on; }
				""");
		Resource serialized = new XMIResourceImpl(URI.createURI("memory:/transitionSerialized.xmi"));
		serialized.getContents().add(EcoreUtil.copy(resource.getContents().get(0)));
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		serialized.save(bytes, Map.of());
		Resource loaded = new XMIResourceImpl(URI.createURI("memory:/transitionLoaded.xmi"));
		loaded.load(new ByteArrayInputStream(bytes.toByteArray()), Map.of());
		TransitionUsage transition = findSingle(loaded, TransitionUsage.class);
		Feature link = UsageUtil.getTransitionLinkFeatureOf(transition);
		List<Relationship> relationships = List.copyOf(transition.getOwnedRelationship());
		// No parser or preparation helper runs after the round trip.
		assertSame(findByName(loaded, "off", StateUsage.class), transition.getSource());
		assertSame(findByName(loaded, "on", StateUsage.class), transition.getTarget());
		assertBinding(bindingsOf(transition), transition.getSuccession(), link);
		assertBinding(bindingsOf(transition), transition.getSource(), TypeUtil.getOwnedParametersOf(transition).get(0));
		assertEquals(relationships, transition.getOwnedRelationship());
	}

	/** Local preparation and source completion preserve an unresolved explicit source without resolving it. */
	@Test
	public void preparationPreservesUnresolvedExplicitSource() {
		// An unresolved `transition first missing then ...` during incremental construction.
		TransitionUsage transition = SysMLFactory.eINSTANCE.createTransitionUsage();
		Membership membership = SysMLFactory.eINSTANCE.createMembership();
		Feature proxy = SysMLFactory.eINSTANCE.createStateUsage();
		((InternalEObject)proxy).eSetProxyURI(URI.createURI("missing.sysml#source"));
		membership.setMemberElement(proxy);
		transition.getOwnedRelationship().add(membership);
		TransitionUtil.prepareStructure(transition);
		TransitionUtil.completeSource(transition);
		assertEquals(List.of(membership), transition.getOwnedRelationship());
		assertSame(proxy, membership.eGet(SysMLPackage.Literals.MEMBERSHIP__MEMBER_ELEMENT, false));
		assertTrue(proxy.eIsProxy());
	}

	/** Alias-based shorthand, entry actions and explicit feature chains retain their selected sources. */
	@Test
	public void sourceCompletionSupportsAliasesEntryActionsAndChains() throws Exception {
		Resource resource = parse("sourceForms.sysml", """
				state def Machine {
					entry action initialize;
					if true then off;
					state off { state nested; }
					state on;
					if true then off;
					alias selected for off;
					transition chained first off.nested then on;
				}
				""");
		Type machine = findByName(resource, "Machine", Type.class);
		List<TransitionUsage> transitions = machine.getOwnedFeature().stream()
				.filter(TransitionUsage.class::isInstance).map(TransitionUsage.class::cast).toList();
		assertSame(findByName(resource, "initialize", Feature.class), transitions.get(0).getSource());
		// The grammar does not allow shorthand immediately after an alias. Build that producer state
		// explicitly: insert the alias before the second transition and clear its source for completion.
		Membership alias = machine.getOwnedMembership().stream()
				.filter(membership -> "selected".equals(membership.getMemberName())).findFirst().orElseThrow();
		machine.getOwnedRelationship().move(machine.getOwnedRelationship().indexOf(transitions.get(1).getOwningRelationship()), alias);
		transitions.get(1).getOwnedMembership().get(0).setMemberElement(null);
		TransitionUtil.completeSource(transitions.get(1));
		// The source selected through the alias is off, rather than the preceding owned state on.
		assertSame(findByName(resource, "off", Feature.class), transitions.get(1).getSource());
		// An explicit chain is retained; preparation must not replace it with the previous state.
		TransitionUsage chained = findByName(resource, "chained", TransitionUsage.class);
		Membership membership = chained.getOwnedMembership().get(0);
		assertTrue(membership instanceof OwningMembership);
		Feature chain = (Feature)membership.getMemberElement();
		assertEquals(2, chain.getOwnedFeatureChaining().size());
		assertSame(findByName(resource, "nested", Feature.class), chained.getSource());
		TransitionUtil.prepareStructure(chained);
		TransitionUtil.completeSource(chained);
		assertSame(chain, chained.getOwnedMembership().get(0).getMemberElement());
	}

	/** Parser preparation keeps a preceding first-reference lazy until ordinary semantic linking. */
	@Test
	public void firstReferenceIsNotResolvedDuringPreparation() throws Exception {
		Resource resource = parse("lazyFirstSource.sysml", """
				action def Procedure {
					decide choice;
					if true then finish;
					action finish;
					private first finish;
					if true then choice;
				}
				""");
		Type owner = findByName(resource, "Procedure", Type.class);
		List<TransitionUsage> transitions = owner.getOwnedFeature().stream()
				.filter(TransitionUsage.class::isInstance).map(TransitionUsage.class::cast).toList();
		TransitionUsage transition = transitions.get(1);
		Membership membership = transition.getOwnedMembership().get(0);
		// Resolving `first finish` during parsing can evaluate decision library rules before
		// the library resources have been loaded (DecisionTest.sysml.xt). Retain its proxy here.
		Object source = membership.eGet(SysMLPackage.Literals.MEMBERSHIP__MEMBER_ELEMENT, false);
		assertTrue(source instanceof InternalEObject && ((InternalEObject)source).eIsProxy());
		assertTrue(resource.getErrors().toString(), resource.getErrors().isEmpty());
		// Once a client requests the source, ordinary linking resolves the captured reference.
		assertSame(findByName(resource, "finish", Feature.class), transition.getSource());
		assertTrue(resource.getErrors().toString(), resource.getErrors().isEmpty());
	}

	/** Action shorthand uses the preceding action and computes the same bindings as a state transition. */
	@Test
	public void actionTransitionUsesPreparedSource() throws Exception {
		Resource resource = parse("actionTransition.sysml", """
				action def Procedure {
					action begin;
					if true then finish;
					action finish;
				}
				""");
		TransitionUsage transition = findSingle(resource, TransitionUsage.class);
		Feature begin = findByName(resource, "begin", Feature.class);
		// No transform or initial semantic query is needed to select the source.
		assertSame(begin, transition.getOwnedMembership().get(0).getMemberElement());
		assertSame(begin, transition.getSource());
		assertSame(findByName(resource, "finish", Feature.class), transition.getTarget());
		assertBinding(bindingsOf(transition), begin, TypeUtil.getOwnedParametersOf(transition).get(0));
		assertEquals(2, bindingsOf(transition).size());
	}

	/** Replacing or removing the succession refreshes only its binding and never creates owned repair elements. */
	@Test
	public void successionEditsRefreshBindingsWithoutReplacingLink() throws Exception {
		Resource resource = parse("successionEdit.sysml", """
				state def Machine { state off; state on; transition t first off then on; }
				""");
		TransitionUsage transition = findSingle(resource, TransitionUsage.class);
		Feature link = UsageUtil.getTransitionLinkFeatureOf(transition);
		Succession original = transition.getSuccession();
		OwningMembership membership = original.getOwningMembership();
		assertEquals(2, bindingsOf(transition).size());
		// A programmatic edit replaces the succession object but leaves the prepared link intact.
		Succession replacement = EcoreUtil.copy(original);
		membership.setOwnedMemberElement(replacement);
		assertBinding(bindingsOf(transition), replacement, link);
		assertEquals(2, bindingsOf(transition).size());
		transition.getOwnedRelationship().remove(membership);
		List<Relationship> incomplete = List.copyOf(transition.getOwnedRelationship());
		assertEquals(1, bindingsOf(transition).size());
		assertEquals(incomplete, transition.getOwnedRelationship());
		transition.getOwnedRelationship().add(membership);
		TransitionUtil.prepareStructure(transition);
		assertSame(link, UsageUtil.getTransitionLinkFeatureOf(transition));
		assertEquals(2, bindingsOf(transition).size());
	}

	/** A failed, reentrant binding calculation releases its guard and succeeds on the next query. */
	@Test
	public void bindingComputationRecoversAfterFailure() throws Exception {
		Resource resource = parse("bindingFailure.sysml", """
				state def Machine { state off; state on; transition t first off then on; }
				""");
		TransitionUsage transition = findSingle(resource, TransitionUsage.class);
		ElementAdapterFactory.removeAdapter(transition);
		TransitionUsageAdapter adapter = new TransitionUsageAdapter(transition) {
			private boolean fail = true;
			@Override
			protected BindingConnector createImplicitBindingConnector(Feature source, Feature target) {
				if (fail) {
					fail = false;
					// Resolving a binding can re-enter the adapted transition before it is complete.
					assertTrue(bindingsOf(transition).isEmpty());
					throw new IllegalStateException("Simulated resolution failure");
				}
				return super.createImplicitBindingConnector(source, target);
			}
		};
		transition.eAdapters().add(adapter);
		assertThrows(IllegalStateException.class, () -> bindingsOf(transition));
		assertEquals(2, bindingsOf(transition).size());
		assertEquals(2, bindingsOf(transition).size());
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
