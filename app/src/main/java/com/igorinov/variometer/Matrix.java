/*
 *  Copyright (C) Ivan Gorinov, 2017
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.igorinov.variometer;

public class Matrix {
    public double[] data;
    int rows;
    int cols;

    public Matrix(int m, int n) {
        rows = m;
        cols = n;
        data = new double[m * n];
    }

    int numRows() {
        return rows;
    }

    int numCols() {
        return cols;
    }

    double itemGet(int i, int j) {
        assert (i < rows);
        assert (j < cols);

        return data[i * cols + j];
    }

    void itemSet(int i, int j, double value) {
        assert (i < rows);
        assert (j < cols);

        data[i * cols + j] = value;
    }

    void itemAdd(int i, int j, double value) {
        assert (i < rows);
        assert (j < cols);

        data[i * cols + j] += value;
    }

    void itemSub(int i, int j, double value) {
        assert (i < rows);
        assert (j < cols);

        data[i * cols + j] -= value;
    }

    void itemMul(int i, int j, double value) {
        assert (i < rows);
        assert (j < cols);

        data[i * cols + j] *= value;
    }

    void zero() {
        int i, j;

        for (i = 0; i < rows; i += 1) {
            for (j = 0; j < cols; j += 1) {
                itemSet(i, j, 0);
            }
        }
    }

    void eye() {
        int i, j;

        assert (rows == cols);
        for (i = 0; i < rows; i += 1) {
            for (j = 0; j < cols; j += 1) {
                if (i == j)
                    itemSet(i, j, 1);
                else
                    itemSet(i, j, 0);
            }
        }
    }

    int inv(Matrix src) {
        assert (rows == cols);

        double a, b, c, d;

        if (rows == 1) {
            a = src.itemGet(0, 0);
            itemSet(0, 0, 1 / a);
        }

        if (rows == 2) {
            double inv_det;
            a = src.itemGet(0, 0);
            b = src.itemGet(0, 1);
            c = src.itemGet(1, 0);
            d = src.itemGet(1, 1);

            inv_det = 1.0 / (a * d - b * c);
            itemSet(0, 0, d * inv_det);
            itemSet(0, 1, -b * inv_det);
            itemSet(1, 0, -c * inv_det);
            itemSet(1, 1, a * inv_det);
        }

        if (rows == 3) {
            double a00, a01, a02;
            double a10, a11, a12;
            double a20, a21, a22;
            double det;

            a00 = src.itemGet(0, 0);
            a01 = src.itemGet(0, 1);
            a02 = src.itemGet(0, 2);
            a10 = src.itemGet(1, 0);
            a11 = src.itemGet(1, 1);
            a12 = src.itemGet(1, 2);
            a20 = src.itemGet(2, 0);
            a21 = src.itemGet(2, 1);
            a22 = src.itemGet(2, 2);

            det = (a00 * a11 * a22 + a01 * a12 * a20 + a02 * a10 * a21)
                    - (a00 * a12 * a21 - a01 * a10 * a22 - a02 * a11 * a20);

            itemSet(0, 0, a11 * a22 - a12 * a21);
            itemSet(1, 0, a12 * a20 - a10 * a22);
            itemSet(2, 0, a10 * a21 - a11 * a20);
            itemSet(0, 1, a21 * a02 - a22 * a01);
            itemSet(1, 1, a22 * a00 - a20 * a02);
            itemSet(2, 1, a20 * a01 - a21 * a00);
            itemSet(0, 2, a01 * a12 - a02 * a11);
            itemSet(1, 2, a02 * a10 - a00 * a12);
            itemSet(2, 2, a00 * a11 - a01 * a10);
            scale(1.0 / det);
        }

        return 0;
    }

    public void copy(Matrix src) {
        assert (src.numRows() == rows);
        assert (src.numCols() == cols);

        System.arraycopy(src.data, 0, data, 0, rows * cols);
    }

    public void transpose(Matrix src) {
        int i, j;

        assert (src.numRows() == cols);
        assert (src.numCols() == rows);

        for (i = 0; i < rows; i += 1) {
            for (j = 0; j < cols; j += 1) {
                itemSet(i, j, src.itemGet(j, i));
            }
        }
    }

    public void add(Matrix src) {
        int i, j;

        assert (src.numRows() == rows);
        assert (src.numCols() == cols);

        for (i = 0; i < rows; i += 1) {
            for (j = 0; j < cols; j += 1) {
                itemAdd(i, j, src.itemGet(i, j));
            }
        }
    }

    // Multiply this matrix by constant

    public void scale(double a) {
        int i, j;

        for (i = 0; i < rows; i += 1) {
            for (j = 0; j < cols; j += 1) {
                itemMul(i, j, a);
            }
        }
    }

    public void shiftUp(int count) {
        int i, j;

        if (count < rows) {
            System.arraycopy(data, count * cols, data, 0, (rows - count) * cols);
            for (i = rows - count; i < rows; i += 1) {
                for (j = 0; j < cols; j += 1) {
                    itemSet(i, j, 0);
                }
            }
        } else {
            zero();
        }
    }

    public void shiftDown(int count) {
        int i, j;

        if (count < rows) {
            System.arraycopy(data, 0, data, count * cols, (rows - count) * cols);
            for (i = 0; i < count; i += 1) {
                for (j = 0; j < cols; j += 1) {
                    itemSet(i, j, 0);
                }
            }
        } else {
            zero();
        }
    }

    public void rowSet(int i, double[] src) {
        assert (src.length == cols);
        System.arraycopy(src, 0, data, i * cols, cols);
    }

    public void rowGet(int i, double[] dst) {
        assert (dst.length == cols);
        System.arraycopy(data, i * cols, dst, 0, cols);
    }

    // Multiply a vector by this matrix
    // dst = this * src

    public void vmul(double[] dst, double[] src) {
        int i, j;
        double sum;

        assert (dst.length == rows);
        assert (src.length == cols);

        for (i = 0; i < rows; i += 1) {
            sum = 0;
            for (j = 0; j < cols; j += 1) {
                sum += src[j] * itemGet(i, j);
            }
            dst[i] = sum;
        }
    }

    // Multiply a vector by this matrix and add to another vector
    // dst += This * src

    public void vmuladd(double[] dst, double[] src) {
        int i, j;
        double sum;

        assert (dst.length == rows);
        assert (src.length == cols);

        for (i = 0; i < rows; i += 1) {
            sum = 0;
            for (j = 0; j < cols; j += 1) {
                sum += src[j] * itemGet(i, j);
            }
            dst[i] += sum;
        }
    }

    // This = A * B

    public void dotProduct(Matrix a, Matrix b) {
        int c, i, j, k;
        double sum;

        assert (a.numRows() == rows);
        assert (a.numCols() == b.numRows());
        assert (b.numCols() == cols);

        c = a.numCols();
        for (i = 0; i < rows; i += 1) {
            for (j = 0; j < cols; j += 1) {
                sum = 0;
                for (k = 0; k < c; k += 1) {
                    sum += a.itemGet(i, k) * b.itemGet(k, j);
                }
                itemSet(i, j, sum);
            }
        }
    }

    // This += A * B

    public void addProduct(Matrix a, Matrix b) {
        int c, i, j, k;
        double sum;

        assert (a.numRows() == rows);
        assert (a.numCols() == b.numRows());
        assert (b.numCols() == cols);

        c = a.numCols();
        for (i = 0; i < rows; i += 1) {
            for (j = 0; j < cols; j += 1) {
                sum = 0;
                for (k = 0; k < c; k += 1) {
                    sum += a.itemGet(i, k) * b.itemGet(k, j);
                }
                itemAdd(i, j, sum);
            }
        }
    }

    // This -= A * B

    public void subProduct(Matrix a, Matrix b) {
        int c, i, j, k;
        double sum;

        assert (a.numRows() == rows);
        assert (a.numCols() == b.numRows());
        assert (b.numCols() == cols);

        c = a.numCols();
        for (i = 0; i < rows; i += 1) {
            for (j = 0; j < cols; j += 1) {
                sum = 0;
                for (k = 0; k < c; k += 1) {
                    sum += a.itemGet(i, k) * b.itemGet(k, j);
                }
                itemSub(i, j, sum);
            }
        }
    }
}
