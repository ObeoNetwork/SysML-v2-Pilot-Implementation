/*******************************************************************************
 * SysML 2 Pilot Implementation
 * Copyright (c) 2026 Obeo
 * Copyright (c) 2021, 2023-2025 Model Driven Solutions, Inc.
 *    
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Eclipse Public License as published by
 * the Eclipse Foundation, version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Eclipse Public License for more details.
 *  
 * You should have received a copy of theEclipse Public License
 * along with this program.  If not, see <https://www.eclipse.org/legal/epl-2.0/>.
 *  
 * @license EPL-2.0 <http://spdx.org/licenses/EPL-2.0>
 *  
 *******************************************************************************/

package org.omg.sysml.adapter;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.eclipse.emf.ecore.EClass;
import org.omg.sysml.lang.sysml.BindingConnector;
import org.omg.sysml.lang.sysml.Feature;
import org.omg.sysml.lang.sysml.TransitionUsage;
import org.omg.sysml.util.TypeUtil;
import org.omg.sysml.util.UsageUtil;

/**
 * Computes detached bindings for prepared transitions without creating owned structure.
 * Specification quotations are from SysML v2.1 Beta 2, Part 1.
 * Like the model it adapts, this adapter requires thread-confined access.
 */
public class TransitionUsageAdapter extends ActionUsageAdapter {

	private boolean isComputingTransitionLinkConnectors;
	private BindingConnector sourceBinding;
	private BindingConnector successionBinding;

	public TransitionUsageAdapter(TransitionUsage element) {
		super(element);
	}

	@Override
	public TransitionUsage getTarget() {
		return (TransitionUsage)super.getTarget();
	}

	/**
	 * Computes binding candidates independently of the existence of prepared links.
	 * Recursive calls leave the in-progress computation to the outer call.
	 *
	 * SysML §8.3.18.9, {@code checkTransitionUsageSourceBindingConnector}:
	 * "A TransitionUsage must have an ownedMember that is a BindingConnector between its source and its first
	 * input parameter (which redefines Actions::TransitionAction::transitionLinkSource)."
	 *
	 * SysML §8.3.18.9, {@code checkTransitionUsageSuccessionBindingConnector}:
	 * "A TransitionUsage must have an ownedMember that is a BindingConnector between its succession and the
	 * inherited Feature TransitionPerformances::TransitionPerformance::transitionLink."
	 */
	protected void computeTransitionLinkConnectors() {
		if (isComputingTransitionLinkConnectors) {
			return;
		}
		isComputingTransitionLinkConnectors = true;
		try {
			TransitionUsage transition = getTarget();
			successionBinding = updateBinding(successionBinding, transition.getSuccession(),
					UsageUtil.getTransitionLinkFeatureOf(transition));
			List<Feature> parameters = TypeUtil.getOwnedParametersOf(transition);
			Feature input = null;
			if (!parameters.isEmpty()) {
				input = parameters.get(0);
			}
			sourceBinding = updateBinding(sourceBinding, transition.getSource(), input);
		} finally {
			isComputingTransitionLinkConnectors = false;
		}
	}

	private BindingConnector updateBinding(BindingConnector previous, Feature source, Feature target) {
		if (source != null && target != null && matches(previous, source, target)) {
			return previous;
		}
		// Replace only this adapter's transition candidates, preserving unrelated bindings.
		implicitMemberBindingConnectors.remove(previous);
		implicitFeatureBindingConnectors.remove(previous);
		if (source == null || target == null) {
			return null;
		}
		for (var member : getTarget().getOwnedMember()) {
			if (member instanceof BindingConnector binding && binding.isImplied() && matches(binding, source, target)) {
				return null; // The explicit materialization operation has already inserted this binding.
			}
		}
		return addBindingConnector(source, target);
	}

	private boolean matches(BindingConnector binding, Feature source, Feature target) {
		if (binding == null) {
			return false;
		}
		// These generated candidates have two owned ends with explicit reference subsettings.
		List<Feature> ends = binding.getOwnedEndFeature();
		return ends.size() == 2
				&& ends.get(0).getOwnedReferenceSubsetting() != null
				&& ends.get(1).getOwnedReferenceSubsetting() != null
				&& ends.get(0).getOwnedReferenceSubsetting().getReferencedFeature() == source
				&& ends.get(1).getOwnedReferenceSubsetting().getReferencedFeature() == target;
	}

	@Override
	public void addAdditionalMembers() {
		computeTransitionLinkConnectors();
	}

	@Override
	public void forEachImplicitBindingConnector(Consumer<BindingConnector> consumer) {
		computeTransitionLinkConnectors();
		super.forEachImplicitBindingConnector(consumer);
	}

	@Override
	public void forEachImplicitBindingConnector(BiConsumer<BindingConnector, EClass> consumer) {
		computeTransitionLinkConnectors();
		super.forEachImplicitBindingConnector(consumer);
	}

	@Override
	public void cleanImplicitBindingConnectors() {
		super.cleanImplicitBindingConnectors();
		sourceBinding = null;
		successionBinding = null;
	}
}
