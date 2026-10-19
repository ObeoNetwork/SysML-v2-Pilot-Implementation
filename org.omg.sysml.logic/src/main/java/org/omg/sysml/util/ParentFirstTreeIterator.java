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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;

/**
 * Visits a root and its containment descendants in depth-first, parent-first order.
 * Children of the last returned object are captured only on the next call to
 * {@link #next()}, so additions and removals made while processing that object
 * are included, even after calls to {@link #hasNext()}.
 * <p>
 * Changes to ancestors or already queued siblings are not reflected in their
 * captured child lists. Moving queued objects during traversal is unsupported.
 * The iterator is confined to the calling thread and does not support removal.
 */
public final class ParentFirstTreeIterator implements TreeIterator<EObject> {

	private final Deque<EObject> pending = new ArrayDeque<>();
	private EObject parent;

	/** Creates a traversal including the non-null root itself. */
	public ParentFirstTreeIterator(EObject root) {
		pending.push(Objects.requireNonNull(root));
	}

	@Override
	public boolean hasNext() {
		return !pending.isEmpty() || parent != null && !parent.eContents().isEmpty();
	}

	@Override
	public EObject next() {
		if (parent != null) {
			// AbstractTreeIterator captures children before returning their parent.
			// Defer this snapshot until next() so processing can prepend children safely;
			// hasNext() must not capture them before the caller finishes that processing.
			List<EObject> children = List.copyOf(parent.eContents());
			parent = null;
			for (int i = children.size() - 1; i >= 0; i--) {
				pending.push(children.get(i));
			}
		}
		parent = pending.pop();
		return parent;
	}

	/** Skips the children of the last returned object, retaining queued siblings. */
	@Override
	public void prune() {
		parent = null;
	}
}
