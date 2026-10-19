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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.EcoreFactory;
import org.junit.Test;

/** Tests containment traversal independently of parsing and model libraries. */
public class ParentFirstTreeIteratorTest {

	/**
	 * Includes the root and all descendants exactly once in depth-first containment
	 * order, with parents preceding their children and references left unvisited.
	 */
	@Test
	public void visitsRootAndDescendantsInContainmentOrder() {
		// Root contains [left [nested], right]; an ESuperPackage reference points outward.
		EPackage outside = node();
		EPackage root = node();
		EPackage left = node();
		EPackage nested = node();
		EPackage right = node();
		outside.getESubpackages().add(root);
		root.getESubpackages().addAll(List.of(left, right));
		left.getESubpackages().add(nested);

		assertEquals(List.of(root, left, nested, right), remaining(new ParentFirstTreeIterator(root)));
	}

	/**
	 * Children prepended or appended while processing a parent, and descendants
	 * added while processing those children, are included in the same traversal.
	 */
	@Test
	public void includesInsertedChildrenAfterHasNext() {
		EPackage root = node();
		EPackage existing = node();
		root.getESubpackages().add(existing);
		ParentFirstTreeIterator iterator = new ParentFirstTreeIterator(root);
		assertSame(root, iterator.next());
		assertTrue(iterator.hasNext());
		assertTrue(iterator.hasNext());

		// Change Root [existing] into Root [prepended [nested], existing, appended].
		// hasNext() above must not have captured the old children.
		EPackage prepended = node();
		EPackage appended = node();
		EPackage nested = node();
		root.getESubpackages().add(0, prepended);
		root.getESubpackages().add(appended);
		assertSame(prepended, iterator.next());
		assertTrue(iterator.hasNext()); // Existing and appended siblings are queued.
		prepended.getESubpackages().add(nested);
		assertEquals(List.of(nested, existing, appended), remaining(iterator));
	}

	/**
	 * A previously childless parent can gain a child after hasNext() returned false;
	 * the new subtree remains available until next() advances past that parent.
	 */
	@Test
	public void includesChildrenAddedToALeaf() {
		EPackage root = node();
		ParentFirstTreeIterator iterator = new ParentFirstTreeIterator(root);
		assertSame(root, iterator.next());
		assertFalse(iterator.hasNext());

		// Add one generation at a time, while its parent is the current object.
		EPackage child = node();
		root.getESubpackages().add(child);
		assertTrue(iterator.hasNext());
		assertSame(child, iterator.next());
		assertFalse(iterator.hasNext());
		EPackage grandchild = node();
		child.getESubpackages().add(grandchild);
		assertEquals(List.of(grandchild), remaining(iterator));
	}

	/**
	 * Removing a child of the current parent after hasNext() excludes that child
	 * without skipping its surviving sibling.
	 */
	@Test
	public void excludesChildrenRemovedBeforeAdvancing() {
		EPackage root = node();
		EPackage removed = node();
		EPackage retained = node();
		root.getESubpackages().addAll(List.of(removed, retained));
		ParentFirstTreeIterator iterator = new ParentFirstTreeIterator(root);
		assertSame(root, iterator.next());
		assertTrue(iterator.hasNext());

		// The children have not yet been captured, so removal must be visible.
		root.getESubpackages().remove(removed);
		assertEquals(List.of(retained), remaining(iterator));
	}

	/**
	 * Pruning skips the current object's descendants, including newly added ones,
	 * but retains its siblings. Pruning the root terminates its traversal.
	 */
	@Test
	public void pruneSkipsDescendantsAndPreservesSiblings() {
		EPackage root = node();
		EPackage left = node();
		EPackage nested = node();
		EPackage right = node();
		root.getESubpackages().addAll(List.of(left, right));
		left.getESubpackages().add(nested);
		ParentFirstTreeIterator iterator = new ParentFirstTreeIterator(root);
		assertSame(root, iterator.next());
		assertSame(left, iterator.next());
		assertTrue(iterator.hasNext());
		iterator.prune();
		left.getESubpackages().add(node());
		assertEquals(List.of(right), remaining(iterator));

		// Pruning the root after hasNext() also prevents traversal of its children.
		iterator = new ParentFirstTreeIterator(root);
		assertSame(root, iterator.next());
		assertTrue(iterator.hasNext());
		iterator.prune();
		assertFalse(iterator.hasNext());
		assertThrows(NoSuchElementException.class, iterator::next);
	}

	/**
	 * Exhaustion consistently throws NoSuchElementException, removal is unsupported,
	 * and constructing a traversal without a root is rejected.
	 */
	@Test
	public void respectsIteratorBoundaryContracts() {
		assertThrows(NullPointerException.class, () -> new ParentFirstTreeIterator(null));
		EPackage root = node();
		ParentFirstTreeIterator iterator = new ParentFirstTreeIterator(root);
		assertTrue(iterator.hasNext());
		assertSame(root, iterator.next());
		assertThrows(UnsupportedOperationException.class, iterator::remove);
		assertFalse(iterator.hasNext());
		assertFalse(iterator.hasNext());
		assertThrows(NoSuchElementException.class, iterator::next);
		assertThrows(NoSuchElementException.class, iterator::next);
		assertFalse(iterator.hasNext());
	}

	/**
	 * Deep containment is traversed without recursive Java calls, preserving every
	 * parent-child identity along the path.
	 */
	@Test
	public void visitsDeepContainmentWithoutRecursion() {
		EPackage root = node();
		List<EObject> expected = new ArrayList<>();
		EPackage parent = root;
		expected.add(parent);
		// Build from the leaf upwards to avoid repeatedly walking a growing ancestor chain.
		for (int i = 0; i < 10000; i++) {
			EPackage ancestor = node();
			ancestor.getESubpackages().add(parent);
			parent = ancestor;
			expected.add(parent);
		}
		java.util.Collections.reverse(expected);
		assertEquals(expected, remaining(new ParentFirstTreeIterator(parent)));
	}

	private static EPackage node() {
		return EcoreFactory.eINSTANCE.createEPackage();
	}

	private static List<EObject> remaining(ParentFirstTreeIterator iterator) {
		List<EObject> visited = new ArrayList<>();
		while (iterator.hasNext()) {
			visited.add(iterator.next());
		}
		return visited;
	}
}
