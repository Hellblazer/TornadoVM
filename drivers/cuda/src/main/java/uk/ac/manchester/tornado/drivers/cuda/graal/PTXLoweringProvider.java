package uk.ac.manchester.tornado.drivers.cuda.graal;

import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.hotspot.HotSpotCallingConventionType;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaField;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.PrimitiveConstant;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;
import org.graalvm.compiler.api.replacements.SnippetReflectionProvider;
import org.graalvm.compiler.core.common.spi.ForeignCallsProvider;
import org.graalvm.compiler.core.common.type.ObjectStamp;
import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.core.common.type.StampPair;
import org.graalvm.compiler.debug.DebugHandlersFactory;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.NodeInputList;
import org.graalvm.compiler.nodes.AbstractDeoptimizeNode;
import org.graalvm.compiler.nodes.CompressionNode;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.FixedNode;
import org.graalvm.compiler.nodes.Invoke;
import org.graalvm.compiler.nodes.InvokeNode;
import org.graalvm.compiler.nodes.LoweredCallTargetNode;
import org.graalvm.compiler.nodes.NamedLocationIdentity;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.PhiNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.UnwindNode;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.calc.FloatConvertNode;
import org.graalvm.compiler.nodes.calc.IntegerDivRemNode;
import org.graalvm.compiler.nodes.calc.MulNode;
import org.graalvm.compiler.nodes.calc.RemNode;
import org.graalvm.compiler.nodes.java.ArrayLengthNode;
import org.graalvm.compiler.nodes.java.InstanceOfNode;
import org.graalvm.compiler.nodes.java.LoadFieldNode;
import org.graalvm.compiler.nodes.java.LoadIndexedNode;
import org.graalvm.compiler.nodes.java.MethodCallTargetNode;
import org.graalvm.compiler.nodes.java.NewArrayNode;
import org.graalvm.compiler.nodes.java.StoreFieldNode;
import org.graalvm.compiler.nodes.java.StoreIndexedNode;
import org.graalvm.compiler.nodes.memory.AbstractWriteNode;
import org.graalvm.compiler.nodes.memory.HeapAccess;
import org.graalvm.compiler.nodes.memory.ReadNode;
import org.graalvm.compiler.nodes.memory.WriteNode;
import org.graalvm.compiler.nodes.memory.address.AddressNode;
import org.graalvm.compiler.nodes.memory.address.OffsetAddressNode;
import org.graalvm.compiler.nodes.spi.LoweringTool;
import org.graalvm.compiler.nodes.type.StampTool;
import org.graalvm.compiler.nodes.util.GraphUtil;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.util.Providers;
import org.graalvm.compiler.replacements.DefaultJavaLoweringProvider;
import org.graalvm.compiler.replacements.SnippetCounter;
import uk.ac.manchester.tornado.drivers.cuda.graal.lir.PTXKind;
import uk.ac.manchester.tornado.drivers.cuda.graal.lir.PTXWriteNode;
import uk.ac.manchester.tornado.drivers.cuda.graal.nodes.CastNode;
import uk.ac.manchester.tornado.drivers.cuda.graal.nodes.FixedArrayNode;
import uk.ac.manchester.tornado.drivers.cuda.graal.nodes.GlobalThreadIdNode;
import uk.ac.manchester.tornado.drivers.cuda.graal.nodes.GlobalThreadSizeNode;
import uk.ac.manchester.tornado.drivers.cuda.graal.nodes.LocalArrayNode;
import uk.ac.manchester.tornado.drivers.cuda.graal.nodes.calc.DivNode;
import uk.ac.manchester.tornado.drivers.cuda.graal.snippets.PTXReduceCPUSnippets;
import uk.ac.manchester.tornado.drivers.cuda.graal.snippets.PTXReduceGPUSnippets;
import uk.ac.manchester.tornado.runtime.TornadoVMConfig;
import uk.ac.manchester.tornado.runtime.graal.nodes.StoreAtomicIndexedNode;
import uk.ac.manchester.tornado.runtime.graal.nodes.TornadoDirectCallTargetNode;
import uk.ac.manchester.tornado.runtime.graal.phases.MarkLocalArray;

import java.util.Iterator;

import static org.graalvm.compiler.nodes.NamedLocationIdentity.ARRAY_LENGTH_LOCATION;
import static uk.ac.manchester.tornado.api.exceptions.TornadoInternalError.shouldNotReachHere;
import static uk.ac.manchester.tornado.api.exceptions.TornadoInternalError.unimplemented;

public class PTXLoweringProvider extends DefaultJavaLoweringProvider {

    private static final boolean USE_ATOMICS = false;
    private final ConstantReflectionProvider constantReflection;
    private static boolean gpuSnippet = false;

    private TornadoVMConfig vmConfig;

    private PTXReduceGPUSnippets.Templates GPUReduceSnippets;
    private PTXReduceCPUSnippets.Templates CPUReduceSnippets;

    public PTXLoweringProvider(MetaAccessProvider metaAccess,
                               ForeignCallsProvider foreignCalls,
                               ConstantReflectionProvider constantReflection,
                               TargetDescription target,
                               boolean useCompressedOops,
                               TornadoVMConfig vmConfig) {
        super(metaAccess, foreignCalls, target, useCompressedOops);
        this.vmConfig = vmConfig;
        this.constantReflection = constantReflection;
    }

    @Override
    public void initialize(OptionValues options, Iterable<DebugHandlersFactory> debugHandlersFactories, SnippetCounter.Group.Factory factory, Providers providers,
                           SnippetReflectionProvider snippetReflection) {
        super.initialize(options, debugHandlersFactories, factory, providers, snippetReflection);
        initializeSnippets(options, debugHandlersFactories, factory, providers, snippetReflection);
    }

    private void initializeSnippets(OptionValues options, Iterable<DebugHandlersFactory> debugHandlersFactories, SnippetCounter.Group.Factory factory, Providers providers,
                                    SnippetReflectionProvider snippetReflection) {
        this.GPUReduceSnippets = new PTXReduceGPUSnippets.Templates(options, debugHandlersFactories, providers, snippetReflection, target);
        this.CPUReduceSnippets = new PTXReduceCPUSnippets.Templates(options, debugHandlersFactories, providers, snippetReflection, target);
    }

    @Override
    protected JavaKind getStorageKind(ResolvedJavaField field) {
        return field.getJavaKind();
    }

    @Override
    public int fieldOffset(ResolvedJavaField field) {
        return field.getOffset();
    }

    @Override
    public ValueNode staticFieldBase(StructuredGraph graph, ResolvedJavaField field) {
        JavaConstant base = constantReflection.asJavaClass(field.getDeclaringClass());
        return ConstantNode.forConstant(base, metaAccess, graph);
    }

    public static boolean isGpuSnippet() {
        return gpuSnippet;
    }

    @Override
    public int arrayLengthOffset() {
        return vmConfig.arrayOopDescLengthOffset();
    }

    @Override
    protected Stamp loadCompressedStamp(ObjectStamp stamp) {
        unimplemented();
        return null;
    }

    @Override
    protected ValueNode newCompressionNode(CompressionNode.CompressionOp op, ValueNode value) {
        unimplemented();
        return null;
    }

    @Override
    protected ValueNode createReadHub(StructuredGraph graph, ValueNode object, LoweringTool tool) {
        unimplemented();
        return null;
    }

    @Override
    protected ValueNode createReadArrayComponentHub(StructuredGraph graph, ValueNode arrayHub, FixedNode anchor) {
        unimplemented();
        return null;
    }

    @Override
    public Integer smallestCompareWidth() {
        // For now don't use this optimization.
        return null;
    }

    @Override
    public boolean supportsBulkZeroing() {
        unimplemented();
        return false;
    }

    @Override
    public void lower(Node node, LoweringTool tool) {
        if (node instanceof Invoke) {
            lowerInvoke((Invoke) node, tool, (StructuredGraph) node.graph());
//        } else if (node instanceof VectorLoadNode) {
//            lowerVectorLoadNode((VectorLoadNode) node);
//        } else if (node instanceof VectorStoreNode) {
//            lowerVectorStoreNode((VectorStoreNode) node);
        } else if (node instanceof AbstractDeoptimizeNode || node instanceof UnwindNode || node instanceof RemNode) {
            /*
             * No lowering, we currently generate LIR directly for these nodes.
             */
        } else if (node instanceof FloatConvertNode) {
            lowerFloatConvertNode((FloatConvertNode) node);
        } else if (node instanceof NewArrayNode) {
            lowerNewArrayNode((NewArrayNode) node);
//        } else if (node instanceof AtomicAddNode) {
//            lowerAtomicAddNode((AtomicAddNode) node, tool);
        } else if (node instanceof LoadIndexedNode) {
            lowerLoadIndexedNode((LoadIndexedNode) node, tool);
        } else if (node instanceof StoreIndexedNode) {
            lowerStoreIndexedNode((StoreIndexedNode) node, tool);
        } else if (node instanceof StoreAtomicIndexedNode) {
            lowerStoreAtomicsReduction(node, tool);
        } else if (node instanceof LoadFieldNode) {
            lowerLoadFieldNode((LoadFieldNode) node, tool);
        } else if (node instanceof StoreFieldNode) {
            lowerStoreFieldNode((StoreFieldNode) node, tool);
        } else if (node instanceof ArrayLengthNode) {
            lowerArrayLengthNode((ArrayLengthNode) node, tool);
        } else if (node instanceof IntegerDivRemNode) {
            lowerIntegerDivRemNode((IntegerDivRemNode) node);
        } else if (node instanceof InstanceOfNode) {
            // ignore InstanceOfNode nodes
        } else {
            super.lower(node, tool);
        }

    }

    private void lowerInvoke(Invoke invoke, LoweringTool tool, StructuredGraph graph) {
        if (invoke.callTarget() instanceof MethodCallTargetNode) {
            MethodCallTargetNode callTarget = (MethodCallTargetNode) invoke.callTarget();
            NodeInputList<ValueNode> parameters = callTarget.arguments();
            ValueNode receiver = parameters.size() <= 0 ? null : parameters.get(0);
            if (!callTarget.isStatic() && receiver.stamp(NodeView.DEFAULT) instanceof ObjectStamp && !StampTool.isPointerNonNull(receiver)) {
                ValueNode nonNullReceiver = createNullCheckedValue(receiver, invoke.asNode(), tool);
                parameters.set(0, nonNullReceiver);
                receiver = nonNullReceiver;
            }
            JavaType[] signature = callTarget.targetMethod().getSignature().toParameterTypes(callTarget.isStatic() ? null : callTarget.targetMethod().getDeclaringClass());

            LoweredCallTargetNode loweredCallTarget = null;

            StampPair returnStampPair = callTarget.returnStamp();
            Stamp returnStamp = returnStampPair.getTrustedStamp();
            if (returnStamp instanceof ObjectStamp) {
                ObjectStamp os = (ObjectStamp) returnStamp;
                ResolvedJavaType type = os.javaType(tool.getMetaAccess());
                PTXKind ptxKind = PTXKind.fromResolvedJavaType(type);
                if (ptxKind != PTXKind.ILLEGAL) {
                    returnStampPair = StampPair.createSingle(PTXStampFactory.getStampFor(ptxKind));
                }
            }

            loweredCallTarget = graph.add(new TornadoDirectCallTargetNode(parameters.toArray(new ValueNode[parameters.size()]), returnStampPair, signature, callTarget.targetMethod(),
                    HotSpotCallingConventionType.JavaCall, callTarget.invokeKind()));

            callTarget.replaceAndDelete(loweredCallTarget);
        }

    }

    private void lowerNewArrayNode(NewArrayNode newArray) {
        final StructuredGraph graph = newArray.graph();
        final ValueNode firstInput = newArray.length();
        if (firstInput instanceof ConstantNode) {
            if (newArray.dimensionCount() == 1) {
                final ConstantNode lengthNode = (ConstantNode) firstInput;
                if (lengthNode.getValue() instanceof PrimitiveConstant) {
                    final int length = ((PrimitiveConstant) lengthNode.getValue()).asInt();
                    ResolvedJavaType elementType = newArray.elementType();
                    JavaKind elementKind = elementType.getJavaKind();
                    final int offset = arrayBaseOffset(elementKind);
                    final int size = offset + (elementKind.getByteCount() * length);
                    if (gpuSnippet) {
                        lowerLocalNewArray(graph, length, newArray);
                    } else {
                        lowerPrivateNewArray(graph, size, newArray);
                    }
                    newArray.clearInputs();
                    GraphUtil.unlinkFixedNode(newArray);
                } else {
                    shouldNotReachHere();
                }
            } else {
                unimplemented("multi-dimensional array declarations are not supported");
            }
        } else {
            unimplemented("dynamically sized array declarations are not supported");
        }
    }

    private void lowerPrivateNewArray(StructuredGraph graph, int size, NewArrayNode newArray) {
        FixedArrayNode fixedArrayNode;
        final ConstantNode newLengthNode = ConstantNode.forInt(size, graph);
        fixedArrayNode = graph.addWithoutUnique(new FixedArrayNode(PTXArchitecture.globalSpace, newArray.elementType(), newLengthNode));
        newArray.replaceAtUsages(fixedArrayNode);
    }

    private void lowerLocalNewArray(StructuredGraph graph, int length, NewArrayNode newArray) {
        LocalArrayNode localArrayNode;
        ConstantNode newLengthNode = ConstantNode.forInt(length, graph);
        localArrayNode = graph.addWithoutUnique(new LocalArrayNode(PTXArchitecture.sharedSpace, newArray.elementType(), newLengthNode));
        newArray.replaceAtUsages(localArrayNode);
    }

    public int arrayBaseOffset(JavaKind kind) {
        return metaAccess.getArrayBaseOffset(kind);
    }

    private void lowerStoreAtomicsReduction(Node node, LoweringTool tool) {
        if (USE_ATOMICS) {
            lowerAtomicStoreIndexedNode((StoreAtomicIndexedNode) node);
        } else {
            lowerReduceSnippets((StoreAtomicIndexedNode) node, tool);
        }
    }

    private void lowerReduceSnippets(StoreAtomicIndexedNode storeIndexed, LoweringTool tool) {
        StructuredGraph graph = storeIndexed.graph();
        ValueNode startIndexNode = storeIndexed.getStartNode();

        // Find Get Global ID node and Global Size;
        GlobalThreadIdNode oclIdNode = graph.getNodes().filter(GlobalThreadIdNode.class).first();
        GlobalThreadSizeNode oclGlobalSize = graph.getNodes().filter(GlobalThreadSizeNode.class).first();

        ValueNode threadID = null;
        Iterator<Node> usages = oclIdNode.usages().iterator();

        boolean cpuScheduler = false;

        while (usages.hasNext()) {
            Node n = usages.next();

            // GPU SCHEDULER
            if (n instanceof PhiNode) {
                gpuSnippet = true;
                threadID = (ValueNode) n;
                break;
            }

            // CPU SCHEDULER
            if (n instanceof MulNode) {
                for (Node n2 : n.usages()) {
                    if (n2 instanceof PhiNode) {
                        threadID = (ValueNode) n2;
                        cpuScheduler = true;
                        break;
                    }
                }
            }
        }
        // Depending on the Scheduler, call the proper snippet factory
        if (cpuScheduler) {
            CPUReduceSnippets.lower(storeIndexed, threadID, oclIdNode, startIndexNode, tool);
        } else {
            GPUReduceSnippets.lower(storeIndexed, threadID, oclGlobalSize, tool);
        }
    }

    private void lowerAtomicStoreIndexedNode(StoreAtomicIndexedNode storeIndexed) {
        unimplemented();
    }


    private void lowerIntegerDivRemNode(IntegerDivRemNode integerDivRemNode) {
        StructuredGraph graph = integerDivRemNode.graph();
        switch (integerDivRemNode.getOp()) {
            case DIV:
                ValueNode div = graph.addOrUnique(DivNode.create(integerDivRemNode.getX(), integerDivRemNode.getY()));
                graph.replaceFixedWithFloating(integerDivRemNode, div);
                break;
            case REM:
                ValueNode rem = graph.addOrUnique(RemNode.create(integerDivRemNode.getX(), integerDivRemNode.getY(), NodeView.DEFAULT));
                graph.replaceFixedWithFloating(integerDivRemNode, rem);
                break;
        }
    }

    @Override
    protected void lowerArrayLengthNode(ArrayLengthNode arrayLengthNode, LoweringTool tool) {
        StructuredGraph graph = arrayLengthNode.graph();
        ValueNode array = arrayLengthNode.array();

        AddressNode address = createOffsetAddress(graph, array, arrayLengthOffset());
        ReadNode arrayLengthRead = graph.add(new ReadNode(address, ARRAY_LENGTH_LOCATION, StampFactory.positiveInt(), HeapAccess.BarrierType.NONE));
        graph.replaceFixedWithFixed(arrayLengthNode, arrayLengthRead);
    }

    @Override
    protected void lowerLoadIndexedNode(LoadIndexedNode loadIndexed, LoweringTool tool) {
        StructuredGraph graph = loadIndexed.graph();
        JavaKind elementKind = loadIndexed.elementKind();
        AddressNode address;

        Stamp loadStamp = loadIndexed.stamp(NodeView.DEFAULT);
        if (!(loadIndexed.stamp(NodeView.DEFAULT) instanceof PTXStamp)) {
            loadStamp = loadStamp(loadIndexed.stamp(NodeView.DEFAULT), elementKind, false);
        }
        address = createArrayAccess(graph, loadIndexed, elementKind);
        ReadNode memoryRead = graph.add(new ReadNode(address, NamedLocationIdentity.getArrayLocation(elementKind), loadStamp, HeapAccess.BarrierType.NONE));
        loadIndexed.replaceAtUsages(memoryRead);
        graph.replaceFixed(loadIndexed, memoryRead);
    }

    @Override
    protected void lowerStoreIndexedNode(StoreIndexedNode storeIndexed, LoweringTool tool) {
        StructuredGraph graph = storeIndexed.graph();
        JavaKind elementKind = storeIndexed.elementKind();
        ValueNode value = storeIndexed.value();
        ValueNode array = storeIndexed.array();
        AddressNode address = createArrayAddress(graph, array, elementKind, storeIndexed.index());
        AbstractWriteNode memoryWrite = createMemWriteNode(elementKind, value, array, address, graph, storeIndexed);
        memoryWrite.setStateAfter(storeIndexed.stateAfter());
        graph.replaceFixedWithFixed(storeIndexed, memoryWrite);
    }

    @Override
    protected void lowerLoadFieldNode(LoadFieldNode loadField, LoweringTool tool) {
        assert loadField.getStackKind() != JavaKind.Illegal;
        StructuredGraph graph = loadField.graph();
        ResolvedJavaField field = loadField.field();
        ValueNode object = loadField.isStatic() ? staticFieldBase(graph, field) : loadField.object();
        Stamp loadStamp = loadStamp(loadField.stamp(NodeView.DEFAULT), field.getJavaKind());
        AddressNode address = createFieldAddress(graph, object, field);
        assert address != null : "Field that is loaded must not be eliminated: " + field.getDeclaringClass().toJavaName(true) + "." + field.getName();
        ReadNode memoryRead = graph.add(new ReadNode(address, fieldLocationIdentity(field), loadStamp, fieldLoadBarrierType(field)));
        loadField.replaceAtUsages(memoryRead);
        graph.replaceFixed(loadField, memoryRead);
    }

    @Override
    protected void lowerStoreFieldNode(StoreFieldNode storeField, LoweringTool tool) {
        StructuredGraph graph = storeField.graph();
        ResolvedJavaField field = storeField.field();
        ValueNode object = storeField.isStatic() ? staticFieldBase(graph, field) : storeField.object();
        AddressNode address = createFieldAddress(graph, object, field);
        assert address != null;
        WriteNode memoryWrite = graph.add(new WriteNode(address, fieldLocationIdentity(field), storeField.value(), fieldStoreBarrierType(storeField.field()), storeField.isVolatile()));
        memoryWrite.setStateAfter(storeField.stateAfter());
        graph.replaceFixedWithFixed(storeField, memoryWrite);
    }

    private void lowerFloatConvertNode(FloatConvertNode floatConvert) {
        final StructuredGraph graph = floatConvert.graph(); // TODO should probably create a specific float-convert node?
        final CastNode asFloat = graph.addWithoutUnique(new CastNode(floatConvert.stamp(NodeView.DEFAULT), floatConvert.getFloatConvert(), floatConvert.getValue()));
        floatConvert.replaceAtUsages(asFloat);
        floatConvert.safeDelete();
    }

    private AddressNode createArrayAccess(StructuredGraph graph, LoadIndexedNode loadIndexed, JavaKind elementKind) {
        AddressNode address;
        if (isLocalIdNode(loadIndexed)) {
            address = createArrayLocalAddress(graph, loadIndexed.array(), loadIndexed.index());
        } else {
            address = createArrayAddress(graph, loadIndexed.array(), elementKind, loadIndexed.index());
        }
        return address;
    }

    private AddressNode createArrayLocalAddress(StructuredGraph graph, ValueNode array, ValueNode index) {
        return graph.unique(new OffsetAddressNode(array, index));
    }

    private boolean isLocalIdNode(LoadIndexedNode loadIndexedNode) {
        // Either the node has as input a LocalArray or has a node which will be lowered to a LocalArray
        Node nd = loadIndexedNode.inputs().first().asNode();
        InvokeNode node = nd.inputs().filter(InvokeNode.class).first();
        boolean willLowerToLocalArrayNode = node != null && "Direct#NewArrayNode.newArray".equals(node.callTarget().targetName()) && gpuSnippet;
        return (nd instanceof MarkLocalArray || willLowerToLocalArrayNode);
    }

    private boolean isLocalIdNode(StoreIndexedNode storeIndexed) {
        // Either the node has as input a LocalArray or has a node which will be lowered to a LocalArray
        Node nd = storeIndexed.inputs().first().asNode();
        InvokeNode node = nd.inputs().filter(InvokeNode.class).first();
        boolean willLowerToLocalArrayNode = node != null && "Direct#NewArrayNode.newArray".equals(node.callTarget().targetName()) && gpuSnippet;
        return (nd instanceof MarkLocalArray || willLowerToLocalArrayNode);
    }

    private AbstractWriteNode createMemWriteNode(JavaKind elementKind, ValueNode value, ValueNode array, AddressNode address, StructuredGraph graph, StoreIndexedNode storeIndexed) {
        AbstractWriteNode memoryWrite;
        if (isSimpleCharOrShort(elementKind, value)) {
            // XXX: This call is due to an error in Graal when storing a variable of type
            // char or short. In future integrations with JVMCI and Graal, this issue is
            // completely solved.
            memoryWrite = graph.add(new PTXWriteNode(address, NamedLocationIdentity.getArrayLocation(elementKind), value, arrayStoreBarrierType(storeIndexed.elementKind()), elementKind));
        } else if (isLocalIdNode(storeIndexed)) {
            address = createArrayLocalAddress(graph, array, storeIndexed.index());
            memoryWrite = graph.add(new WriteNode(address, NamedLocationIdentity.getArrayLocation(elementKind), value, arrayStoreBarrierType(storeIndexed.elementKind()), true));
        } else {
            memoryWrite = graph.add(new WriteNode(address, NamedLocationIdentity.getArrayLocation(elementKind), value, arrayStoreBarrierType(storeIndexed.elementKind()), true));
        }
        return memoryWrite;
    }

    private boolean isSimpleCharOrShort(JavaKind elementKind, ValueNode value) {
        return (elementKind == JavaKind.Char && value.getStackKind() != JavaKind.Object) || (elementKind == JavaKind.Short && value.getStackKind() != JavaKind.Object);
    }
}
