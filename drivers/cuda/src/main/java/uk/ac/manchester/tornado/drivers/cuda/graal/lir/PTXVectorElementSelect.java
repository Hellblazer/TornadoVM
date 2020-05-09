/*
 * This file is part of Tornado: A heterogeneous programming framework: 
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2013-2019, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
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
package uk.ac.manchester.tornado.drivers.cuda.graal.lir;

import jdk.vm.ci.meta.Value;
import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.lir.Opcode;
import org.graalvm.compiler.lir.Variable;
import uk.ac.manchester.tornado.api.exceptions.TornadoInternalError;
import uk.ac.manchester.tornado.drivers.cuda.graal.asm.PTXAssembler;
import uk.ac.manchester.tornado.drivers.cuda.graal.compiler.PTXCompilationResultBuilder;

@Opcode("VSEL")
public class PTXVectorElementSelect extends PTXLIROp {

    final Value vector;
    private final Value selection;

    public PTXVectorElementSelect(LIRKind lirKind, Value vector, Value selection) {
        super(lirKind);
        this.vector = vector;
        this.selection = selection;
    }

    @Override
    public void emit(PTXCompilationResultBuilder crb, PTXAssembler asm, Variable dest) {
        TornadoInternalError.unimplemented();
//        asm.emitValueOrOp(crb, vector);
//        asm.emitSymbol(".s");
//        asm.emitValue(crb, selection);
    }

    @Override
    public String toString() {
        return String.format("vselect(%s, %s)", vector, selection);
    }
}
