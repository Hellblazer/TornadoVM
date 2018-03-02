/*
 * This file is part of Tornado: A heterogeneous programming framework: 
 * https://github.com/beehive-lab/tornado
 *
 * Copyright (c) 2013-2018, APT Group, School of Computer Science,
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
package uk.ac.manchester.tornado.collections.types;

import static java.lang.Math.cos;
import static java.lang.Math.sin;
import static uk.ac.manchester.tornado.collections.math.TornadoMath.sqrt;
import static uk.ac.manchester.tornado.collections.types.Float3.add;
import static uk.ac.manchester.tornado.collections.types.Float3.cross;
import static uk.ac.manchester.tornado.collections.types.Float3.dot;
import static uk.ac.manchester.tornado.collections.types.Float3.mult;
import static uk.ac.manchester.tornado.common.exceptions.TornadoInternalError.unimplemented;

public class FloatSE3 {

    final Matrix4x4Float matrix = new Matrix4x4Float();
    Float3 translation = new Float3();

    public FloatSE3() {

    }

    public static Matrix4x4Float toMatrix4(float[] v) {
        Matrix4x4Float result = new Matrix4x4Float();

        result.identity();

        result.set(0, 1, -v[5]);
        result.set(0, 2, v[4]);
        result.set(1, 2, -v[3]);

        result.set(1, 0, v[5]);
        result.set(2, 0, -v[4]);
        result.set(2, 1, v[3]);

        result.set(0, 3, v[0]);
        result.set(1, 3, v[1]);
        result.set(2, 3, v[2]);

        return result;
    }

    public static Matrix4x4Float toMatrix4(Float6 v) {
        Matrix4x4Float result = new Matrix4x4Float();

        result.identity();

        result.set(0, 1, -v.get(5));
        result.set(0, 2, v.get(4));
        result.set(1, 2, -v.get(3));

        result.set(1, 0, v.get(5));
        result.set(2, 0, -v.get(4));
        result.set(2, 1, v.get(3));

        result.set(0, 3, v.get(0));
        result.set(1, 3, v.get(1));
        result.set(2, 3, v.get(2));

        return result;
    }

    public FloatSE3(Float6 v) {
        matrix.identity();
        exp(v);

        Float3 value = v.getHi();
        matrix.set(0, 3, value.getX());
        matrix.set(1, 3, value.getY());
        matrix.set(2, 3, value.getZ());

        /*
         * Matrix.set(matrix,1,0, -v[2]); Matrix.set(matrix,2,0, v[1]);
         * Matrix.set(matrix,2,1, -v[0]);
         *
         * Matrix.set(matrix,0,1, v[2]); Matrix.set(matrix,0,2, -v[1]);
         * Matrix.set(matrix,1,2, v[0]);
         *
         * Matrix.set(matrix,3,0, v[3]); Matrix.set(matrix,3,1, v[4]);
         * Matrix.set(matrix,3,2, v[5]);
         */
        //Matrix.set(matrix, 3, 3, 1f);
        //System.out.printf("matrix=\n%s\n",matrix.toString());
    }

    public void exp(Float6 mu) {
        final float one_6th = 1f / 6f;
        final float one_20th = 1f / 20f;

        //VectorFloat3 result = new VectorFloat3(v.());
        Float3 mu_lo = mu.getHi();
        Float3 w = mu.getLo();
        final float theta_sq = dot(w, w);
        final float theta = sqrt(theta_sq);

        float A, B, C;

        Float3 crossProduct = cross(w, mu_lo);

        //final Float3 translation = result.get(0);
        if (theta_sq < 1e-8f) {
            A = 1f - one_6th * theta_sq;
            B = 0.5f;
            translation = add(mu_lo, mult(crossProduct, 0.5f));
        } else {
            if (theta_sq < 1e-6f) {
                C = one_6th * (1 - one_20th * theta_sq);
                A = 1 - theta_sq * C;
                B = (float) (0.5 - 0.25 * one_6th * theta_sq);
            } else {
                final float inv_theta = 1f / theta;
                A = (float) (sin(theta) * inv_theta);
                B = (float) ((1 - cos(theta)) * (sq(inv_theta)));
                C = (1 - A) * (sq(inv_theta));
            }

            Float3 wcp = cross(w, crossProduct);
            Float3 Btmp = add(mult(crossProduct, B), mult(wcp, C));
            translation = add(mu_lo, Btmp);

        }
        rodrigues_so3_exp(w, A, B);

    }

    private void rodrigues_so3_exp(Float3 w, float A, float B) {

        {
            final float wx2 = sq(w.getX());
            final float wy2 = sq(w.getY());
            final float wz2 = sq(w.getZ());

            matrix.set(0, 0, 1f - B * (wy2 + wz2));
            matrix.set(1, 1, 1f - B * (wx2 + wz2));
            matrix.set(2, 2, 1f - B * (wx2 + wy2));
        }

        {
            final float a = A * w.getZ();
            final float b = B * (w.getX() * w.getY());
            matrix.set(0, 1, b - a);
            matrix.set(1, 0, b + a);
        }

        {
            final float a = A * w.getY();
            final float b = B * (w.getX() * w.getZ());
            matrix.set(0, 2, b + a);
            matrix.set(2, 0, b - a);
        }

        {
            final float a = A * w.getX();
            final float b = B * (w.getY() * w.getZ());
            matrix.set(1, 2, b - a);
            matrix.set(2, 1, b + a);
        }
    }

    public Float3 getTranslation() {
        return matrix.column(3).asFloat3();
    }

    public void setTranslation(Float3 trans) {
        for (int i = 0; i < 3; i++) {
            matrix.column(3).asFloat3().set(trans);
        }
    }

    public Matrix4x4Float getMatrix() {
        return matrix;
    }

    public void multiply(FloatSE3 m) {
        unimplemented();
    }

    private float sq(float value) {
        return value * value;
    }

    // converts a SE3 into a 4x4 matrix
    public Matrix4x4Float toMatrix4() {

        Matrix4x4Float R = new Matrix4x4Float();
        R.set(matrix);

        R.set(3, 3, 1f);

        return R;
    }

    @Override
    public String toString() {
        return matrix.toString();
    }
}