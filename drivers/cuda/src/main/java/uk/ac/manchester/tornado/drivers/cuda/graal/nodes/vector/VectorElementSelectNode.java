/*
 * Copyright (c) 2018, 2019, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
 * Copyright (c) 2009, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Authors: James Clarkson
 *
 */
package uk.ac.manchester.tornado.drivers.cuda.graal.nodes.vector;

import jdk.vm.ci.meta.Value;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.NodeClass;
import org.graalvm.compiler.nodeinfo.InputType;
import org.graalvm.compiler.nodeinfo.NodeInfo;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.calc.FloatingNode;
import org.graalvm.compiler.nodes.spi.LIRLowerable;
import org.graalvm.compiler.nodes.spi.NodeLIRBuilderTool;
import uk.ac.manchester.tornado.drivers.cuda.graal.asm.PTXAssembler;
import uk.ac.manchester.tornado.drivers.cuda.graal.lir.PTXBinary;
import uk.ac.manchester.tornado.drivers.cuda.graal.lir.PTXKind;

import static uk.ac.manchester.tornado.api.exceptions.TornadoInternalError.guarantee;
import static uk.ac.manchester.tornado.api.exceptions.TornadoInternalError.unimplemented;

/**
 * The {@code LoadIndexedNode} represents a read from an element of an array.
 */
@NodeInfo(nameTemplate = ".{p#selection}")
public class VectorElementSelectNode extends FloatingNode implements LIRLowerable {

    public static final NodeClass<VectorElementSelectNode> TYPE = NodeClass.create(VectorElementSelectNode.class);

    @Input(InputType.Extension) ValueNode vector;

    @Input ValueNode selection;

    public VectorElementSelectNode(PTXKind kind, ValueNode vector, ValueNode selection) {
        super(TYPE, StampFactory.forKind(kind.asJavaKind()));
        this.vector = vector;
        this.selection = selection;
    }

    @Override
    public boolean inferStamp() {
        return true;
        // return updateStamp(createStamp(vector, kind.getElementKind()));
    }

    public ValueNode getSelection() {
        return selection;
    }

    public ValueNode getVector() {
        return vector;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {

        unimplemented();

//        guarantee(vector != null, "vector operand is null");
//        Value targetVector = gen.operand(getVector());
//        Value selectValue = gen.operand(getSelection());
//
//        guarantee(targetVector != null, "vector value is null 2");
//        guarantee(selectValue != null, "select value is null");
//        final PTXBinary.Selector expr = new PTXBinary.Selector(PTXAssembler.PTXBinaryOp.VECTOR_SELECT, gen.getLIRGeneratorTool().getLIRKind(stamp), targetVector, selectValue);
//        gen.setResult(this, expr);

    }

}
