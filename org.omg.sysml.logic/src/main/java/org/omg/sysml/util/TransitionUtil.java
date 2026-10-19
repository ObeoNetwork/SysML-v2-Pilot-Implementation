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
package org.omg.sysml.util;

import java.util.List;

import org.omg.sysml.lang.sysml.Connector;
import org.omg.sysml.lang.sysml.Element;
import org.omg.sysml.lang.sysml.Feature;
import org.omg.sysml.lang.sysml.Membership;
import org.omg.sysml.lang.sysml.OwningMembership;
import org.omg.sysml.lang.sysml.ParameterMembership;
import org.omg.sysml.lang.sysml.Relationship;
import org.omg.sysml.lang.sysml.Succession;
import org.omg.sysml.lang.sysml.SysMLFactory;
import org.omg.sysml.lang.sysml.SysMLPackage;
import org.omg.sysml.lang.sysml.TransitionUsage;
import org.omg.sysml.lang.sysml.Type;

/** Explicit preparation of transition structure for textual and programmatic producers. */
public final class TransitionUtil {

	private TransitionUtil() {
	}

	/**
	 * Ensures a source membership and, when a succession exists, a transition-link
	 * reference. This idempotent operation inspects only owned structure and does
	 * not resolve references. Existing memberships, parameters and links are retained.
	 */
	public static void prepareStructure(TransitionUsage transition) {
		List<Membership> memberships = transition.getOwnedMembership();
		if (memberships.isEmpty() || memberships.get(0) instanceof ParameterMembership) {
			transition.getOwnedRelationship().add(0, SysMLFactory.eINSTANCE.createMembership());
		}
		if (UsageUtil.getTransitionLinkFeatureOf(transition) != null) {
			return;
		}
		for (Relationship relationship : transition.getOwnedRelationship()) {
			if (relationship instanceof OwningMembership membership
					&& membership.getOwnedMemberElement() instanceof Succession) {
				TypeUtil.addOwnedFeatureTo(transition, SysMLFactory.eINSTANCE.createReferenceUsage());
				return;
			}
		}
	}

	/**
	 * Fills an empty source membership from the preceding eligible feature after
	 * owned structure has been prepared. This operation may resolve preceding aliases;
	 * call it after parser post-processing or after establishing programmatic ownership.
	 * A non-null source, including an unresolved explicit reference, is never replaced.
	 * Moving a prepared transition therefore preserves its selected source. A missing
	 * source remains null until the producer explicitly completes it again.
	 */
	public static void completeSource(TransitionUsage transition) {
		Membership source = getEmptySourceMembership(transition);
		if (source != null) {
			source.setMemberElement(UsageUtil.getPreviousFeature(transition));
		}
	}

	/**
	 * Captures the lexical source without resolving references during parser preparation.
	 * A preceding source reference such as {@code first A;} may still be an Xtext
	 * proxy: retain that reference and let normal linking resolve it in its original
	 * context. The parser guarantees the syntactic source position; unlike
	 * {@link #completeSource(TransitionUsage)}, this operation cannot semantically
	 * classify an unresolved alias. Existing non-null source references are preserved.
	 */
	public static void prepareSourceReference(TransitionUsage transition) {
		Membership source = getEmptySourceMembership(transition);
		if (source == null || !(transition.getOwningNamespace() instanceof Type owner)) {
			return;
		}
		List<Membership> memberships = owner.getOwnedMembership();
		for (int i = memberships.indexOf(transition.getOwningMembership()) - 1; i >= 0; i--) {
			Element previous = (Element)memberships.get(i).eGet(SysMLPackage.Literals.MEMBERSHIP__MEMBER_ELEMENT, false);
			if (previous != null && previous.eIsProxy()) {
				source.setMemberElement(previous);
				return;
			}
			if (previous instanceof Feature feature && !FeatureUtil.isParameter(feature)
					&& !(feature instanceof TransitionUsage)
					&& (!(feature instanceof Connector) || UsageUtil.isMessage(feature))) {
				source.setMemberElement(feature);
				return;
			}
		}
	}

	private static Membership getEmptySourceMembership(TransitionUsage transition) {
		List<Membership> memberships = transition.getOwnedMembership();
		if (!memberships.isEmpty()) {
			Membership source = memberships.get(0);
			if (!(source instanceof ParameterMembership)
					&& source.eGet(SysMLPackage.Literals.MEMBERSHIP__MEMBER_ELEMENT, false) == null) {
				return source;
			}
		}
		return null;
	}
}
