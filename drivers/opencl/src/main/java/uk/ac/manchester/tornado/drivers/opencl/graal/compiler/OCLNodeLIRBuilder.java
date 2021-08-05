/*
 * Copyright (c) 2020, APT Group, Department of Computer Science,
 * School of Engineering, The University of Manchester. All rights reserved.
 * Copyright (c) 2018, 2020, APT Group, Department of Computer Science,
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
package uk.ac.manchester.tornado.drivers.opencl.graal.compiler;

import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.Local;
import jdk.vm.ci.meta.PrimitiveConstant;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Value;
import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.core.common.calc.Condition;
import org.graalvm.compiler.core.common.cfg.BlockMap;
import org.graalvm.compiler.core.common.type.ObjectStamp;
import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.gen.NodeLIRBuilder;
import org.graalvm.compiler.core.gen.NodeMatchRules;
import org.graalvm.compiler.core.match.ComplexMatchValue;
import org.graalvm.compiler.debug.TTY;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.lir.ConstantValue;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.LIRFrameState;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LabelRef;
import org.graalvm.compiler.lir.StandardOp.LabelOp;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.lir.gen.LIRGenerator.Options;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool.BlockScope;
import org.graalvm.compiler.nodes.AbstractEndNode;
import org.graalvm.compiler.nodes.AbstractMergeNode;
import org.graalvm.compiler.nodes.BreakpointNode;
import org.graalvm.compiler.nodes.DirectCallTargetNode;
import org.graalvm.compiler.nodes.EndNode;
import org.graalvm.compiler.nodes.FixedNode;
import org.graalvm.compiler.nodes.IfNode;
import org.graalvm.compiler.nodes.IndirectCallTargetNode;
import org.graalvm.compiler.nodes.Invoke;
import org.graalvm.compiler.nodes.LogicNode;
import org.graalvm.compiler.nodes.LoopBeginNode;
import org.graalvm.compiler.nodes.LoopEndNode;
import org.graalvm.compiler.nodes.LoopExitNode;
import org.graalvm.compiler.nodes.LoweredCallTargetNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.ParameterNode;
import org.graalvm.compiler.nodes.PhiNode;
import org.graalvm.compiler.nodes.SafepointNode;
import org.graalvm.compiler.nodes.ShortCircuitOrNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.ValuePhiNode;
import org.graalvm.compiler.nodes.calc.FloatEqualsNode;
import org.graalvm.compiler.nodes.calc.FloatLessThanNode;
import org.graalvm.compiler.nodes.calc.IntegerBelowNode;
import org.graalvm.compiler.nodes.calc.IntegerEqualsNode;
import org.graalvm.compiler.nodes.calc.IntegerLessThanNode;
import org.graalvm.compiler.nodes.calc.IntegerTestNode;
import org.graalvm.compiler.nodes.calc.IsNullNode;
import org.graalvm.compiler.nodes.cfg.Block;
import org.graalvm.compiler.nodes.extended.SwitchNode;
import org.graalvm.compiler.options.OptionValues;
import uk.ac.manchester.tornado.api.exceptions.TornadoInternalError;
import uk.ac.manchester.tornado.api.exceptions.TornadoRuntimeException;
import uk.ac.manchester.tornado.drivers.opencl.graal.OCLStampFactory;
import uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLBinaryIntrinsicCmp;
import uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLBinaryOp;
import uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLNullaryOp;
import uk.ac.manchester.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLUnaryOp;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLBinary;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLBuiltinTool;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLControlFlow;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLDirectCall;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLKind;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLLIROp;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLLIRStmt;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLLIRStmt.AssignStmt;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLLIRStmt.ExprStmt;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLNullary;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLReturnSlot;
import uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLUnary;
import uk.ac.manchester.tornado.drivers.opencl.graal.nodes.IntelUnrollPragmaNode;
import uk.ac.manchester.tornado.drivers.opencl.graal.nodes.FPGAWorkGroupSizeNode;
import uk.ac.manchester.tornado.drivers.opencl.graal.nodes.XilinxPipeliningPragmaNode;
import uk.ac.manchester.tornado.drivers.opencl.graal.nodes.logic.LogicalAndNode;
import uk.ac.manchester.tornado.drivers.opencl.graal.nodes.logic.LogicalEqualsNode;
import uk.ac.manchester.tornado.drivers.opencl.graal.nodes.logic.LogicalNotNode;
import uk.ac.manchester.tornado.drivers.opencl.graal.nodes.logic.LogicalOrNode;
import uk.ac.manchester.tornado.drivers.opencl.graal.nodes.vector.VectorValueNode;

import java.util.Collection;
import java.util.List;

import static uk.ac.manchester.tornado.api.exceptions.TornadoInternalError.shouldNotReachHere;
import static uk.ac.manchester.tornado.api.exceptions.TornadoInternalError.unimplemented;
import static uk.ac.manchester.tornado.drivers.opencl.graal.lir.OCLKind.ILLEGAL;
import static uk.ac.manchester.tornado.runtime.TornadoCoreRuntime.getDebugContext;
import static uk.ac.manchester.tornado.runtime.graal.compiler.TornadoCodeGenerator.trace;

public class OCLNodeLIRBuilder extends NodeLIRBuilder {

    private LIRKind resolveStamp(Stamp stamp) {
        LIRKind lirKind = LIRKind.Illegal;
        if (!stamp.isEmpty()) {
            if (stamp instanceof ObjectStamp) {
                ObjectStamp os = (ObjectStamp) stamp;
                ResolvedJavaType type = os.javaType(gen.getMetaAccess());
                OCLKind oclKind = OCLKind.fromResolvedJavaType(type);
                if (oclKind != OCLKind.ILLEGAL) {
                    lirKind = LIRKind.value(oclKind);
                } else {
                    lirKind = gen.getLIRKind(stamp);
                }
            } else {
                lirKind = gen.getLIRKind(stamp);
            }
        }
        return lirKind;
    }

    @Override
    public void emitInvoke(Invoke x) {
        LoweredCallTargetNode callTarget = (LoweredCallTargetNode) x.callTarget();

        final Stamp stamp = x.asNode().stamp(NodeView.DEFAULT);
        LIRKind lirKind = resolveStamp(stamp);
        AllocatableValue result = Value.ILLEGAL;

        if (lirKind != LIRKind.Illegal) {
            result = gen.newVariable(lirKind);
        }

        CallingConvention invokeCc = new CallingConvention(0, result);
        gen.getResult().getFrameMapBuilder().callsMethod(invokeCc);

        Value[] parameters = visitInvokeArguments(invokeCc, callTarget.arguments());

        LIRFrameState callState = null;
        if (callTarget instanceof DirectCallTargetNode) {
            emitDirectCall((DirectCallTargetNode) callTarget, result, parameters, AllocatableValue.NONE, callState);
        } else if (callTarget instanceof IndirectCallTargetNode) {
            emitIndirectCall((IndirectCallTargetNode) callTarget, result, parameters, AllocatableValue.NONE, callState);
        } else {
            shouldNotReachHere();
        }

        if (isLegal(result)) {
            setResult(x.asNode(), result);
        }
    }

    @Override
    public Value[] visitInvokeArguments(CallingConvention invokeCc, Collection<ValueNode> arguments) {
        final Value[] values = new Value[arguments.size()];
        int j = 0;
        for (ValueNode arg : arguments) {
            if (arg != null) {
                Value operand = operand(arg);
                values[j] = operand;
                j++;
            } else {
                throw shouldNotReachHere("I thought we no longer have null entries for two-slot types...");
            }
        }
        return values;
    }

    public static boolean isIllegal(Value value) {
        assert value != null;
        return Value.ILLEGAL.equals(value);
    }

    public static boolean isLegal(Value value) {
        return !isIllegal(value);
    }

    private boolean elseClause;

    public OCLNodeLIRBuilder(final StructuredGraph graph, final LIRGeneratorTool gen, NodeMatchRules nodeMatchRules) {
        super(graph, gen, nodeMatchRules);
    }

    public void doBlock(final Block block, final StructuredGraph graph, final BlockMap<List<Node>> blockMap, boolean isKernel) {
        OptionValues options = graph.getOptions();
        trace("%s - block %s", graph.method().getName(), block);
        try (BlockScope blockScope = gen.getBlockScope(block)) {

            if (block == gen.getResult().getLIR().getControlFlowGraph().getStartBlock()) {
                assert block.getPredecessorCount() == 0;
                emitPrologue(graph, isKernel);
            }

            final List<Node> nodes = blockMap.get(block);

            // Allow NodeLIRBuilder subclass to specialise code generation of any
            // interesting groups of instructions
            matchComplexExpressions(block, graph.getLastSchedule());

            for (int i = 0; i < nodes.size(); i++) {
                final Node node = nodes.get(i);
                if (node instanceof ValueNode) {
                    final ValueNode valueNode = (ValueNode) node;
                    // System.out.printf("do block: node=%s\n", valueNode);
                    if (Options.TraceLIRGeneratorLevel.getValue(options) >= 3) {
                        TTY.println("LIRGen for " + valueNode);
                    }

                    if (!hasOperand(valueNode)) {
                        if (!peephole(valueNode)) {
                            try {
                                doRoot(valueNode);
                                platformPatch(isKernel);
                            } catch (final Throwable e) {
                                System.out.println("e: " + e.toString());
                                e.printStackTrace();
                                throw new TornadoInternalError(e).addContext(valueNode.toString());
                            }
                        }
                    } else {
                        Value operand = operand(valueNode);
                        if (ComplexMatchValue.INTERIOR_MATCH.equals(operand)) {
                            // Doesn't need to be evaluated
                            getDebugContext().log("interior match for %s", valueNode);
                        } else if (operand instanceof ComplexMatchValue) {
                            getDebugContext().log("complex match for %s", valueNode);
                            final ComplexMatchValue match = (ComplexMatchValue) operand;
                            operand = match.evaluate(this);
                            if (operand != null) {
                                setResult(valueNode, operand);
                            }
                        } else if (valueNode instanceof VectorValueNode) {
                            // There can be cases in which the result of an
                            // instruction is already set before by other
                            // instructions. case where vector value is used as an input to a phi
                            // node before it is assigned to
                            final VectorValueNode vectorNode = (VectorValueNode) valueNode;
                            vectorNode.generate(this);
                        }
                    }
                }
            }
            assert LIR.verifyBlock(gen.getResult().getLIR(), block);
        }
    }

    private void doRoot(ValueNode instr) {
        getDebugContext().log("Visiting %s", instr);
        emitNode(instr);
        if (hasOperand(instr)) {
            getDebugContext().log("Operand for %s = %s", instr, operand(instr));
        }
    }

    private void platformPatch(boolean isKernel) {
        final List<LIRInstruction> insns = getLIRGeneratorTool().getResult().getLIR().getLIRforBlock(gen.getCurrentBlock());
        final int index = insns.size() - 1;
        final LIRInstruction op = insns.get(index);

        if (!isKernel) {
            return;
        }

        if (op instanceof ExprStmt) {
            ExprStmt expr = (ExprStmt) op;
            if (expr.getExpr() instanceof OCLUnary.Expr && ((OCLUnary.Expr) expr.getExpr()).getOpcode().equals(OCLUnaryOp.RETURN)) {
                OCLUnary.Expr returnExpr = (OCLUnary.Expr) expr.getExpr();
                append(new ExprStmt(new OCLNullary.Expr(OCLNullaryOp.RETURN, LIRKind.value(OCLKind.ILLEGAL))));
                insns.remove(index);
                LIRKind lirKind = LIRKind.value(returnExpr.getPlatformKind());
                final AllocatableValue slotAddress = new OCLReturnSlot(lirKind);
                // double check this works properly
                insns.set(index, new AssignStmt(slotAddress, returnExpr.getValue()));
            }
        }

    }

    private Value emitNegatedLogicNode(final LogicNode node) {
        Value result;
        trace("emitLogicNode: %s", node);
        LIRKind intLirKind = LIRKind.value(OCLKind.INT);
        LIRKind boolLirKind = LIRKind.value(OCLKind.BOOL);
        if (node instanceof LogicalEqualsNode) {
            final LogicalEqualsNode condition = (LogicalEqualsNode) node;
            final Value x = operandOrConjunction(condition.getX());
            final Value y = operandOrConjunction(condition.getY());
            result = getGen().getArithmetic().genBinaryExpr(OCLBinaryOp.RELATIONAL_NE, boolLirKind, x, y);
        } else if (node instanceof FloatEqualsNode) {
            final FloatEqualsNode condition = (FloatEqualsNode) node;
            final Value x = operand(condition.getX());
            final Value y = operand(condition.getY());
            result = getGen().getArithmetic().genBinaryExpr(OCLBinaryIntrinsicCmp.FLOAT_IS_NOT_EQUAL, intLirKind, x, y);
        } else if (node instanceof FloatLessThanNode) {
            final FloatLessThanNode condition = (FloatLessThanNode) node;
            final Value x = operand(condition.getX());
            final Value y = operand(condition.getY());
            result = getGen().getArithmetic().genBinaryExpr(OCLBinaryIntrinsicCmp.FLOAT_IS_GREATEREQUAL, intLirKind, x, y);
        } else if (node instanceof IntegerBelowNode) {
            final IntegerBelowNode condition = (IntegerBelowNode) node;
            final Value x = operand(condition.getX());
            final Value y = operand(condition.getY());
            result = getGen().getArithmetic().genBinaryExpr(OCLBinaryOp.RELATIONAL_GTE, boolLirKind, x, y);
        } else if (node instanceof IntegerEqualsNode) {
            final IntegerEqualsNode condition = (IntegerEqualsNode) node;
            final Value x = operand(condition.getX());
            final Value y = operand(condition.getY());
            result = getGen().getArithmetic().genBinaryExpr(OCLBinaryOp.RELATIONAL_NE, boolLirKind, x, y);
        } else if (node instanceof IntegerLessThanNode) {
            final IntegerLessThanNode condition = (IntegerLessThanNode) node;
            final Value x = operand(condition.getX());
            final Value y = operand(condition.getY());
            result = getGen().getArithmetic().genBinaryExpr(OCLBinaryOp.RELATIONAL_GTE, boolLirKind, x, y);
        } else if (node instanceof IsNullNode) {
            final IsNullNode condition = (IsNullNode) node;
            final Value value = operand(condition.getValue());
            result = getGen().getArithmetic().genBinaryExpr(OCLBinaryOp.RELATIONAL_NE, boolLirKind, value, new ConstantValue(intLirKind, PrimitiveConstant.NULL_POINTER));
        } else if (node instanceof IntegerTestNode) {
            final IntegerTestNode testNode = (IntegerTestNode) node;
            final Value x = operand(testNode.getX());
            final Value y = operand(testNode.getY());
            result = getGen().getArithmetic().genTestNegateBinaryExpr(OCLBinaryOp.BITWISE_AND, boolLirKind, x, y);
        } else {
            throw new TornadoRuntimeException(String.format("logic node (class=%s)", node.getClass().getName()));
        }
        setResult(node, result);
        return (OCLLIROp) result;
    }

    private OCLLIROp emitLogicNode(final LogicNode node) {
        Value result;
        trace("emitLogicNode: %s", node);
        LIRKind intLirKind = LIRKind.value(OCLKind.INT);
        LIRKind boolLirKind = LIRKind.value(OCLKind.BOOL);
        if (node instanceof LogicalEqualsNode) {
            final LogicalEqualsNode condition = (LogicalEqualsNode) node;
            final Value x = operandOrConjunction(condition.getX());
            final Value y = operandOrConjunction(condition.getY());
            result = getGen().getArithmetic().genBinaryExpr(OCLBinaryOp.RELATIONAL_EQ, boolLirKind, x, y);
        } else if (node instanceof LogicalOrNode) {
            final LogicalOrNode condition = (LogicalOrNode) node;
            final Value x = operandOrConjunction(condition.getX());
            final Value y = operandOrConjunction(condition.getY());
            result = getGen().getArithmetic().genBinaryExpr(OCLBinaryOp.LOGICAL_OR, boolLirKind, x, y);
        } else if (node instanceof LogicalAndNode) {
            final LogicalAndNode condition = (LogicalAndNode) node;
            final Value x = operandOrConjunction(condition.getX());
            final Value y = operandOrConjunction(condition.getY());
            result = getGen().getArithmetic().genBinaryExpr(OCLBinaryOp.LOGICAL_AND, boolLirKind, x, y);
        } else if (node instanceof LogicalNotNode) {
            final LogicalNotNode condition = (LogicalNotNode) node;
            final Value value = operandOrConjunction(condition.getValue());
            result = getGen().getArithmetic().genUnaryExpr(OCLUnaryOp.LOGICAL_NOT, boolLirKind, value);
        } else if (node instanceof FloatEqualsNode) {
            final FloatEqualsNode condition = (FloatEqualsNode) node;
            final Value x = operand(condition.getX());
            final Value y = operand(condition.getY());
            result = getGen().getArithmetic().genBinaryExpr(OCLBinaryIntrinsicCmp.FLOAT_IS_EQUAL, intLirKind, x, y);
        } else if (node instanceof FloatLessThanNode) {
            final FloatLessThanNode condition = (FloatLessThanNode) node;
            final Value x = operand(condition.getX());
            final Value y = operand(condition.getY());
            result = getGen().getArithmetic().genBinaryExpr(OCLBinaryIntrinsicCmp.FLOAT_IS_LESS, intLirKind, x, y);
        } else if (node instanceof IntegerBelowNode) {
            final IntegerBelowNode condition = (IntegerBelowNode) node;
            final Value x = operand(condition.getX());
            final Value y = operand(condition.getY());
            result = getGen().getArithmetic().genBinaryExpr(OCLBinaryOp.RELATIONAL_LT, boolLirKind, x, y);
        } else if (node instanceof IntegerEqualsNode) {
            final IntegerEqualsNode condition = (IntegerEqualsNode) node;
            final Value x = operand(condition.getX());
            final Value y = operand(condition.getY());
            result = getGen().getArithmetic().genBinaryExpr(OCLBinaryOp.RELATIONAL_EQ, boolLirKind, x, y);
        } else if (node instanceof IntegerLessThanNode) {
            final IntegerLessThanNode condition = (IntegerLessThanNode) node;
            final Value x = operand(condition.getX());
            final Value y = operand(condition.getY());
            result = getGen().getArithmetic().genBinaryExpr(OCLBinaryOp.RELATIONAL_LT, boolLirKind, x, y);
        } else if (node instanceof IsNullNode) {
            final IsNullNode condition = (IsNullNode) node;
            final Value value = operand(condition.getValue());
            result = getGen().getArithmetic().genBinaryExpr(OCLBinaryOp.RELATIONAL_EQ, boolLirKind, value, new ConstantValue(intLirKind, PrimitiveConstant.NULL_POINTER));
        } else if (node instanceof ShortCircuitOrNode) {
            final ShortCircuitOrNode condition = (ShortCircuitOrNode) node;
            final Value x = operandOrConjunction(condition.getX());
            final Value y = operandOrConjunction(condition.getY());
            result = getGen().getArithmetic().genBinaryExpr(OCLBinaryOp.LOGICAL_OR, boolLirKind, x, y);
        } else if (node instanceof IntegerTestNode) {
            final IntegerTestNode testNode = (IntegerTestNode) node;
            final Value x = operand(testNode.getX());
            final Value y = operand(testNode.getY());
            result = getGen().getArithmetic().genTestBinaryExpr(OCLBinaryOp.BITWISE_AND, boolLirKind, x, y);
        } else {
            throw new TornadoRuntimeException(String.format("logic node (class=%s)", node.getClass().getName()));
        }
        setResult(node, result);
        return (OCLLIROp) result;
    }

    private Value operandOrConjunction(ValueNode value) {
        if (operand(value) != null) {
            return operand(value);
        } else if (value instanceof LogicNode) {
            return emitLogicNode((LogicNode) value);
        } else {
            shouldNotReachHere();
        }
        return null;
    }

    @Override
    protected void emitDirectCall(final DirectCallTargetNode callTarget, final Value result, final Value[] parameters, final Value[] temps, final LIRFrameState callState) {
        final OCLDirectCall call = new OCLDirectCall(callTarget, result, parameters, callState);
        if (isLegal(result)) {
            append(new OCLLIRStmt.AssignStmt(gen.asAllocatable(result), call));
        } else {
            append(new OCLLIRStmt.ExprStmt(call));
        }
    }

    @Override
    protected void emitIndirectCall(final IndirectCallTargetNode arg0, final Value arg1, final Value[] arg2, final Value[] arg3, final LIRFrameState arg4) {
        unimplemented();
    }

    @Override
    public void emitIf(final IfNode x) {
        trace("emitIf: %s, condition=%s\n", x, x.condition().getClass().getName());

        /*
         * test to see if this is an exception check need to implement this properly? or
         * omit!
         */
        final LabelRef falseBranch = getLIRBlock(x.falseSuccessor());
        if (falseBranch.getTargetBlock().isExceptionEntry()) {
            trace("emitExceptionEntry");
            shouldNotReachHere("exceptions are unimplemented");
        }

        final boolean isLoop = gen.getCurrentBlock().isLoopHeader();
        final boolean invertedLoop = isLoop && x.trueSuccessor() instanceof LoopExitNode;

        final Value condition = (invertedLoop) ? emitNegatedLogicNode(x.condition()) : emitLogicNode(x.condition());
        trace("condition: %s -> %s", x.condition(), condition);

        if (isLoop) {
            // HERE NEED TO ADD THE PRAGMA UNROLL
            append(new OCLControlFlow.LoopConditionOp(condition));
        } else if (elseClause) {
            append(new OCLControlFlow.LinkedConditionalBranchOp(condition));
        } else {
            Value operand = operand(x.condition());
            Variable newVariable = getGen().newVariable(operand.getValueKind());
            append(new AssignStmt(newVariable, operand));
            append(new OCLControlFlow.ConditionalBranchOp(newVariable));
        }
    }

    private void emitLoopBegin(final LoopBeginNode loopBeginNode) {

        trace("visiting emitLoopBegin %s", loopBeginNode);

        final Block block = (Block) gen.getCurrentBlock();
        final Block currentBlockDominator = block.getDominator();
        final LIR lir = getGen().getResult().getLIR();
        final LabelOp label = (LabelOp) lir.getLIRforBlock(block).get(0);

        List<ValuePhiNode> valuePhis = loopBeginNode.valuePhis().snapshot();
        for (ValuePhiNode phi : valuePhis) {
            final Value value = operand(phi.firstValue());
            if (phi.singleBackValueOrThis() == phi && value instanceof Variable) {
                /*
                 * preserve loop-carried dependencies outside of loops
                 */
                setResult(phi, value);
            } else {
                final AllocatableValue result = gen.asAllocatable(operandForPhi(phi));
                append(new OCLLIRStmt.AssignStmt(result, value));
            }
        }
        emitOCLFPGAPragmas(currentBlockDominator);
        append(new OCLControlFlow.LoopInitOp());
        append(new OCLControlFlow.LoopPostOp());
        label.clearIncomingValues();
    }

    @Override
    public void visitLoopEnd(final LoopEndNode loopEnd) {
        trace("visiting LoopEndNode: %s", loopEnd);

        final LoopBeginNode loopBegin = loopEnd.loopBegin();
        final List<ValuePhiNode> phis = loopBegin.valuePhis().snapshot();

        for (ValuePhiNode phi : phis) {
            AllocatableValue dest = gen.asAllocatable(operandForPhi(phi));
            Value src = operand(phi.valueAt(loopEnd));

            if (!dest.equals(src)) {
                append(new OCLLIRStmt.AssignStmt(dest, src));
            }
        }
    }

    @Override
    public void visitMerge(final AbstractMergeNode mergeNode) {
        trace("visitMerge: ", mergeNode);

        boolean loopExitMerge = true;
        for (EndNode end : mergeNode.forwardEnds()) {
            loopExitMerge &= end.predecessor() instanceof LoopExitNode;
        }

        for (ValuePhiNode phi : mergeNode.valuePhis()) {
            final ValueNode value = phi.singleValueOrThis();
            if (value != phi) {
                AllocatableValue dest = gen.asAllocatable(operandForPhi(phi));
                Value src = operand(value);

                if (!dest.equals(src)) {
                    append(new OCLLIRStmt.AssignStmt(dest, src));
                }
            } else if (loopExitMerge) {
                AllocatableValue dest = gen.asAllocatable(operandForPhi(phi));
                Value src = operand(phi.valueAt(1));

                append(new OCLLIRStmt.AssignStmt(dest, src));
            }
        }
    }

    @Override
    protected void emitNode(final ValueNode node) {
        trace("emitNode: %s", node);
        System.out.printf("emitNode: %s\n", node);
        if (node instanceof LoopBeginNode) {
            emitLoopBegin((LoopBeginNode) node);
        } else if (node instanceof LoopExitNode) {
            emitLoopExit((LoopExitNode) node);
        } else if (node instanceof ShortCircuitOrNode) {
            emitShortCircuitOrNode((ShortCircuitOrNode) node);
        } else if (node instanceof IntelUnrollPragmaNode || node instanceof XilinxPipeliningPragmaNode || node instanceof FPGAWorkGroupSizeNode) {
            // ignore emit-action
        } else {
            super.emitNode(node);
        }
    }

    @Override
    public void emitSwitch(SwitchNode x) {
        assert x.defaultSuccessor() != null;
        LabelRef defaultTarget = getLIRBlock(x.defaultSuccessor());
        int keyCount = x.keyCount();
        if (keyCount == 0) {
            gen.emitJump(defaultTarget);
        } else {
            Variable value = gen.load(operand(x.value()));
            if (keyCount == 1) {
                assert defaultTarget != null;
                unimplemented();
            } else {
                LabelRef[] keyTargets = new LabelRef[keyCount];
                JavaConstant[] keyConstants = new JavaConstant[keyCount];
                double[] keyProbabilities = new double[keyCount];
                for (int i = 0; i < keyCount; i++) {
                    keyTargets[i] = getLIRBlock(x.keySuccessor(i));
                    keyConstants[i] = (JavaConstant) x.keyAt(i);
                    keyProbabilities[i] = x.keyProbability(i);
                }
                gen.emitStrategySwitch(keyConstants, keyProbabilities, keyTargets, defaultTarget, value);
            }
        }
    }

    private void emitShortCircuitOrNode(ShortCircuitOrNode node) {
        LIRKind lirKind = LIRKind.value(OCLKind.BOOL);
        final Variable result = gen.newVariable(lirKind);
        final Value x = operandOrConjunction(node.getX());
        final Value y = operandOrConjunction(node.getY());
        append(new AssignStmt(result, new OCLBinary.Expr(OCLBinaryOp.LOGICAL_OR, lirKind, x, y)));
        setResult(node, result);
    }

    private void emitLoopExit(LoopExitNode node) {
        if (!node.loopBegin().getBlockNodes().contains((FixedNode) node.predecessor())) {
            append(new OCLControlFlow.LoopBreakOp());
        }
    }

    protected void emitPrologue(final StructuredGraph graph, boolean isKernel) {
        if (isKernel) {
            for (final ParameterNode param : graph.getNodes(ParameterNode.TYPE)) {
                setResult(param, getGen().getOCLGenTool().emitParameterLoad(param, param.index()));
            }
        } else {
            final Local[] locals = graph.method().getLocalVariableTable().getLocalsAt(0);
            for (final ParameterNode param : graph.getNodes(ParameterNode.TYPE)) {
                LIRKind lirKind = getGen().getLIRKind(param.stamp(NodeView.DEFAULT));
                setResult(param, new OCLNullary.Parameter(locals[param.index()].getName(), lirKind));
            }
        }
    }

    private void emitOCLFPGAPragmas(Block blk) {
        for (ValueNode tempDomBlockNode : blk.getNodes()) {
            if (tempDomBlockNode instanceof IntelUnrollPragmaNode || tempDomBlockNode instanceof XilinxPipeliningPragmaNode) {
                super.emitNode(tempDomBlockNode);
            }
        }
    }

    private OCLLIRGenerator getGen() {
        return (OCLLIRGenerator) gen;
    }

    private OCLBuiltinTool getBuiltinTool() {
        return getGen().getOCLBuiltinTool();
    }

    @Override
    protected boolean peephole(final ValueNode value) {
        return false;
    }

    // FIXME: Remove this code
    public String toOpenCLSymbol(final Condition condition) {
        switch (condition) {
            case AE:
                return ">=";
            case AT:
                return ">";
            case BE:
                return "=<";
            case BT:
                return "<";
            case EQ:
                return "==";
            case GE:
                return ">=";
            case GT:
                return ">";
            case LE:
                return "=<";
            case LT:
                return "<";
            case NE:
                return "!=";
            default:
                return String.format("<invalid op (%s)>", condition.operator);
        }
    }

    @Override
    public void visitBreakpointNode(final BreakpointNode arg0) {
        unimplemented();
    }

    @Override
    public void visitEndNode(final AbstractEndNode end) {
        trace("visitEnd: %s", end);

        if (end instanceof LoopEndNode) {
            return;
        }

        // Move the phi assignment outside the loop.
        // Only do that for the phi nodes that are not inside "else { break; }" blocks.
        Block curBlock = (Block) gen.getCurrentBlock();
        boolean shouldRelocateInstructions = false;
        if (curBlock.getBeginNode() instanceof LoopExitNode) {
            LoopExitNode loopExitNode = (LoopExitNode) curBlock.getBeginNode();
            LoopBeginNode loopBeginNode = loopExitNode.loopBegin();
            Block loopBeginBlock = loopBeginNode.graph().getLastSchedule().getNodeToBlockMap().get(loopBeginNode);
            for (Block pred : curBlock.getPredecessors()) {
                if (pred == loopBeginBlock) {
                    shouldRelocateInstructions = true;
                    break;
                }
            }
        }

        /*
         * It generates instructions that are relocated from within the for-loop to
         * after the for-loop. https://github.com/beehive-lab/TornadoVM/pull/129
         */
        if (shouldRelocateInstructions) {
            append(new OCLLIRStmt.MarkRelocateInstruction());
        }

        final AbstractMergeNode merge = end.merge();
        for (ValuePhiNode phi : merge.valuePhis()) {
            final ValueNode value = phi.valueAt(end);
            if (!phi.isLoopPhi() && phi.singleValueOrThis() == phi || (value instanceof PhiNode && !((PhiNode) value).isLoopPhi())) {
                final AllocatableValue result = gen.asAllocatable(operandForPhi(phi));
                append(new OCLLIRStmt.AssignStmt(result, operand(value)));
            }
        }
    }

    public Value operandForPhi(ValuePhiNode phi) {
        Value result = operand(phi);
        if (result == null) {
            Variable newOperand = gen.newVariable(getPhiKind(phi));
            setResult(phi, newOperand);
            return newOperand;
        } else {
            return result;
        }
    }

    @Override
    protected LIRKind getPhiKind(PhiNode phi) {
        Stamp stamp = phi.stamp(NodeView.DEFAULT);
        if (stamp.isEmpty()) {
            for (ValueNode n : phi.values()) {
                if (stamp.isEmpty()) {
                    stamp = n.stamp(NodeView.DEFAULT);
                } else {
                    stamp = stamp.meet(n.stamp(NodeView.DEFAULT));
                }
            }
            phi.setStamp(stamp);
        } else if (stamp instanceof ObjectStamp) {
            ObjectStamp oStamp = (ObjectStamp) stamp;
            OCLKind oclKind = OCLKind.fromResolvedJavaType(oStamp.javaType(gen.getMetaAccess()));
            if (oclKind != ILLEGAL && oclKind.isVector()) {
                stamp = OCLStampFactory.getStampFor(oclKind);
                phi.setStamp(stamp);
            }
        }
        return gen.getLIRKind(stamp);
    }

    @Override
    public void visitSafepointNode(final SafepointNode arg0) {
        unimplemented();
    }
}
