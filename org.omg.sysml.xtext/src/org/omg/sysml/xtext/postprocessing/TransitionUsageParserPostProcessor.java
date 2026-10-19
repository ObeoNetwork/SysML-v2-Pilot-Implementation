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
package org.omg.sysml.xtext.postprocessing;

import org.omg.sysml.lang.sysml.TransitionUsage;
import org.omg.sysml.util.TransitionUtil;

/** Prepares transition structure and captures its source reference without resolving it. */
public class TransitionUsageParserPostProcessor extends ActionUsageParserPostProcessor {

	public TransitionUsageParserPostProcessor(TransitionUsage element) {
		super(element);
	}

	@Override
	public TransitionUsage getTarget() {
		return (TransitionUsage)super.getTarget();
	}

	@Override
	public void postProcess() {
		super.postProcess();
		TransitionUtil.prepareStructure(getTarget());
		TransitionUtil.prepareSourceReference(getTarget());
	}
}
