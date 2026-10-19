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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.xtext.EcoreUtil2;
import org.junit.BeforeClass;
import org.junit.Test;
import org.omg.kerml.xtext.postprocessing.ElementParserPostProcessor;
import org.omg.sysml.lang.sysml.ActionDefinition;
import org.omg.sysml.lang.sysml.ActionUsage;
import org.omg.sysml.lang.sysml.Element;
import org.omg.sysml.lang.sysml.FeatureMembership;
import org.omg.sysml.lang.sysml.SysMLFactory;
import org.omg.sysml.util.TypeUtil;
import org.omg.sysml.xtext.SysMLStandaloneSetup;
import org.omg.sysml.xtext.scoping.SysMLLinker;

import com.google.inject.Injector;

/** Exercises post-processing that changes the children of the element being visited. */
public class ParserPostProcessorTraversalTest {

	private static Injector injector;

	/** Installs the real parser and post-processors without loading model libraries. */
	@BeforeClass
	public static void setupParser() {
		injector = new SysMLStandaloneSetup().createInjectorAndDoEMFRegistration();
	}

	/**
	 * Prepending and appending children during processing preserves the existing
	 * children and visits the new subtrees exactly once in depth-first containment order.
	 */
	@Test
	public void visitsInsertedChildrenAndTheirDescendantsInOrder() throws Exception {
		Resource resource = parse("insertedChildren.sysml", """
				action def Root { action originalOne; action originalTwo; }
				""");
		ActionDefinition root = EcoreUtil2.getAllContentsOfType(resource.getContents().get(0), ActionDefinition.class).get(0);
		ActionUsage inserted = action("'inserted'");
		ActionUsage nested = action("'nested'");
		ActionUsage appended = action("'appended'");
		// During the pass, turn the parsed model into:
		// action def Root { action inserted { action nested; } action originalOne; action originalTwo; action appended; }
		// Quoted names on the added nodes prove that their real post-processors execute.
		RecordingLinker linker = new RecordingLinker(element -> {
			if (element == root) {
				FeatureMembership membership = SysMLFactory.eINSTANCE.createFeatureMembership();
				membership.setOwnedMemberFeature(inserted);
				root.getOwnedRelationship().add(0, membership);
				TypeUtil.addOwnedFeatureTo(root, appended);
			} else if (element == inserted) {
				TypeUtil.addOwnedFeatureTo(inserted, nested);
			}
		});
		injector.injectMembers(linker);
		linker.process(resource);

		// Capturing child iterators before the parent callback can skip or repeat these nodes.
		assertEquals(List.of("Root", "inserted", "nested", "originalOne", "originalTwo", "appended"), linker.visitedNames());
		assertEquals("inserted", inserted.getDeclaredName());
		assertEquals("nested", nested.getDeclaredName());
		assertEquals("appended", appended.getDeclaredName());
		linker.assertVisitedOnce();
	}

	/**
	 * A node with no children when reached can create a subtree that is processed
	 * in the same pass, rather than being discarded as an already visited leaf.
	 */
	@Test
	public void visitsChildrenAddedToAnInitiallyEmptyNode() throws Exception {
		Resource resource = parse("emptyParent.sysml", "action def Root;");
		ActionDefinition root = EcoreUtil2.getAllContentsOfType(resource.getContents().get(0), ActionDefinition.class).get(0);
		ActionUsage child = action("'child'");
		ActionUsage grandchild = action("'grandchild'");
		// Construct action def Root { action child { action grandchild; } } incrementally,
		// adding each generation only when its initially empty parent is processed.
		RecordingLinker linker = new RecordingLinker(element -> {
			if (element == root) {
				TypeUtil.addOwnedFeatureTo(root, child);
			} else if (element == child) {
				TypeUtil.addOwnedFeatureTo(child, grandchild);
			}
		});
		injector.injectMembers(linker);
		linker.process(resource);
		assertEquals(List.of("Root", "child", "grandchild"), linker.visitedNames());
		assertEquals("grandchild", grandchild.getDeclaredName());
		linker.assertVisitedOnce();
	}

	private static Resource parse(String name, String text) throws Exception {
		Resource resource = injector.getInstance(ResourceSet.class).createResource(URI.createURI("memory:/" + name));
		resource.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), Map.of());
		assertEquals(List.of(), resource.getErrors());
		return resource;
	}

	private static ActionUsage action(String name) {
		ActionUsage action = SysMLFactory.eINSTANCE.createActionUsage();
		action.setDeclaredName(name);
		return action;
	}

	private static class RecordingLinker extends SysMLLinker {
		private final List<Element> visited = new ArrayList<>();
		private final Consumer<Element> preparation;

		RecordingLinker(Consumer<Element> preparation) {
			this.preparation = preparation;
		}

		void process(Resource resource) {
			postProcessAll(resource.getContents().get(0));
		}

		@Override
		protected ElementParserPostProcessor doGetPostProcessor(Element element) {
			ElementParserPostProcessor processor = super.doGetPostProcessor(element);
			return new ElementParserPostProcessor(element) {
				@Override
				public void postProcess() {
					visited.add(element);
					preparation.accept(element);
					processor.postProcess();
				}
			};
		}

		List<String> visitedNames() {
			return visited.stream().map(Element::getDeclaredName).filter(name -> name != null).toList();
		}

		void assertVisitedOnce() {
			Set<Element> unique = Collections.newSetFromMap(new IdentityHashMap<>());
			unique.addAll(visited);
			assertEquals("Every element must be visited exactly once", unique.size(), visited.size());
		}
	}
}
