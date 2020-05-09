/*
 * Copyright (c) 2020, APT Group, Department of Computer Science,
 * School of Engineering, The University of Manchester. All rights reserved.
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

import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.Value;
import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.core.common.type.ObjectStamp;
import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.NodeClass;
import org.graalvm.compiler.lir.ConstantValue;
import org.graalvm.compiler.nodeinfo.InputType;
import org.graalvm.compiler.nodeinfo.NodeInfo;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.calc.FloatingNode;
import org.graalvm.compiler.nodes.spi.LIRLowerable;
import org.graalvm.compiler.nodes.spi.NodeLIRBuilderTool;
import uk.ac.manchester.tornado.drivers.cuda.graal.PTXStamp;
import uk.ac.manchester.tornado.drivers.cuda.graal.lir.PTXKind;
import uk.ac.manchester.tornado.drivers.cuda.graal.lir.PTXVectorElementSelect;

import static uk.ac.manchester.tornado.api.exceptions.TornadoInternalError.guarantee;
import static uk.ac.manchester.tornado.api.exceptions.TornadoInternalError.shouldNotReachHere;

/**
 * The {@code LoadIndexedNode} represents a read from an element of an array.
 */
@NodeInfo(nameTemplate = "Op .s{p#lane}")
public abstract class VectorElementOpNode extends FloatingNode implements LIRLowerable, Comparable<VectorElementOpNode> {

    public static final NodeClass<VectorElementOpNode> TYPE = NodeClass.create(VectorElementOpNode.class);

    @Input(InputType.Extension) ValueNode vector;

    @Input ValueNode lane;

    protected final PTXKind ptxKind;

    protected VectorElementOpNode(NodeClass<? extends VectorElementOpNode> c, PTXKind kind, ValueNode vector, ValueNode lane) {
        super(c, StampFactory.forKind(kind.asJavaKind()));
        this.ptxKind = kind;
        this.vector = vector;
        this.lane = lane;

        Stamp vstamp = vector.stamp(NodeView.DEFAULT);
        PTXKind vectorKind = PTXKind.ILLEGAL;
        if (vstamp instanceof ObjectStamp) {
            ObjectStamp ostamp = (ObjectStamp) vector.stamp(NodeView.DEFAULT);
            // System.out.printf("ostamp: type=%s\n", ostamp.type());

            if (ostamp.type() != null) {
                vectorKind = PTXKind.fromResolvedJavaType(ostamp.type());
                guarantee(vectorKind.isVector(), "Cannot apply vector operation to non-vector type: %s", vectorKind);
                guarantee(vectorKind.getVectorLength() >= laneId(), "Invalid lane %d on type %s", laneId(), ptxKind);
            }
        } else if (vstamp instanceof PTXStamp) {
            final PTXStamp vectorStamp = (PTXStamp) vector.stamp(NodeView.DEFAULT);
            vectorKind = vectorStamp.getPTXKind();
            guarantee(vectorKind.isVector(), "Cannot apply vector operation to non-vector type: %s", vectorKind);
            guarantee(vectorKind.getVectorLength() >= laneId(), "Invalid lane %d on type %s", laneId(), ptxKind);
        } else {
            shouldNotReachHere("invalid type on vector operation: %s (stamp=%s (class=%s))", vector, vector.stamp(NodeView.DEFAULT), vector.stamp(NodeView.DEFAULT).getClass().getName());
        }

    }

    @Override
    public int compareTo(VectorElementOpNode o) {
        return Integer.compare(laneId(), o.laneId());
    }

    @Override
    public boolean inferStamp() {
        // return false;
        return updateStamp(StampFactory.forKind(ptxKind.asJavaKind()));
    }

    final public int laneId() {
        guarantee(lane instanceof ConstantNode, "Invalid lane: %s", lane);
        return (lane instanceof ConstantNode) ? lane.asJavaConstant().asInt() : -1;
    }

    public ValueNode getVector() {
        return vector;
    }

    public PTXKind getPTXKind() {
        return ptxKind;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        guarantee(vector != null, "vector is null");
        // System.out.printf("vector = %s,
        // origin=%s\n",vector,vector.getOrigin());
        Value targetVector = gen.operand(getVector());
        // if (targetVector == null && vector.getOrigin() instanceof Invoke) {
        // targetVector = gen.operand(vector.getOrigin());
        // }

        guarantee(targetVector != null, "vector is null 2");
        final PTXVectorElementSelect element = new PTXVectorElementSelect(gen.getLIRGeneratorTool().getLIRKind(stamp), targetVector,
                new ConstantValue(LIRKind.value(PTXKind.S32), JavaConstant.forInt(laneId())));
        gen.setResult(this, element);

    }

}
