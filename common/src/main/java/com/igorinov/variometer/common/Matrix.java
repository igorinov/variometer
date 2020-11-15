/*
 *  Copyright (C) Ivan Gorinov, 2017
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.igorinov.variometer.common;

import org.ejml.data.DMatrixRMaj;

public class Matrix extends DMatrixRMaj {

    public Matrix(int m, int n) {
        super(m, n);
    }

    public void shiftUp(int count) {
        int i, j;

        if (count < numRows) {
            System.arraycopy(data, count * numCols, data, 0, (numRows - count) * numCols);
            for (i = numRows - count; i < numRows; i += 1) {
                for (j = 0; j < numCols; j += 1) {
                    set(i, j, 0);
                }
            }
        } else {
            zero();
        }
    }

    public void shiftDown(int count) {
        int i, j;

        if (count < numRows) {
            System.arraycopy(data, 0, data, count * numCols, (numRows - count) * numCols);
            for (i = 0; i < count; i += 1) {
                for (j = 0; j < numCols; j += 1) {
                    set(i, j, 0);
                }
            }
        } else {
            zero();
        }
    }
}
