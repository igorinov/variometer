/*
 *  Copyright (C) Ivan Gorinov, 2017
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.igorinov.variometer.common;

import org.ejml.data.DMatrixRMaj;

import java.util.Arrays;

public class Matrix extends DMatrixRMaj {

    public Matrix(int m, int n) {
        super(m, n);
    }

    public void zeroRows(int start, int end) {
        Arrays.fill(data, start * numCols, end * numCols, 0);
    }

    public void shiftUp(int count) {
        if (count < numRows) {
            System.arraycopy(data, count * numCols, data, 0, (numRows - count) * numCols);
            zeroRows(numRows - count, numRows);
        } else {
            zero();
        }
    }

    public void shiftDown(int count) {
        if (count < numRows) {
            System.arraycopy(data, 0, data, count * numCols, (numRows - count) * numCols);
            zeroRows(0, count);
        } else {
            zero();
        }
    }
}
