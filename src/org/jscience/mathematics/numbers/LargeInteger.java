/*
 * jScience - Java(TM) Tools and Libraries for the Advancement of Sciences.
 * Copyright (C) 2004 - The jScience Consortium (http://jscience.org/)
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation (http://www.gnu.org/copyleft/lesser.html); either version
 * 2.1 of the License, or any later version.
 */
package org.jscience.mathematics.numbers;


import java.io.IOException;

import javolution.realtime.ArrayPool;
import javolution.realtime.ConcurrentContext;
import javolution.realtime.ObjectPool;
import javolution.realtime.PoolContext;
import javolution.realtime.ConcurrentContext.Logic;
import javolution.realtime.LocalContext.Variable;
import javolution.util.FastMap;
import javolution.util.MathLib;
import javolution.lang.Text;
import javolution.lang.TextBuilder;
import javolution.xml.XmlElement;
import javolution.xml.XmlFormat;

import org.jscience.mathematics.matrices.Matrix;
import org.jscience.mathematics.matrices.Operable;

/**
 * <p> This class represents an immutable integer number of arbitrary size.</p>
 * <p> It has the following advantages over the 
 *     <code>java.math.BigInteger</code> class:
 * <ul>
 *     <li> Optimized for 64 bits architectures. But still runs significantly 
 *          faster on 32 bits processors.</li>
 *     <li> Real-time compliant for improved performance and predictability.
 *          No temporary object allocated on the heap and no garbage collection
 *          if executions are performed within a {@link PoolContext}
 *          (e.g. {@link #add add} operation <b>5x</b> faster).</li>
 *     <li> Implements the {@link Operable} interface for modular
 *          arithmetic and can be used in conjonction with the {@link Matrix}
 *          class to resolve modulo equations (ref. number theory).</li>
 *     <li> Improved algorithms (e.g. Concurrent Karabutsa multiplication in 
 *          O(n<sup>Log3</sup>) instead of O(n<sup>2</sup>).</li>
 * </ul></p>
 * 
 * @author <a href="mailto:jean-marie@dautelle.com">Jean-Marie Dautelle</a>
 * @version 1.0, October 24, 2004
 * @see     #setModulus setModulus
 * @see     <a href="http://mathworld.wolfram.com/KaratsubaMultiplication.html">
 *          Karatsuba Multiplication -- from MathWorld</a>
 */
public final class LargeInteger extends RealtimeNumber implements Comparable {

    /**
     * Holds the default XML representation for large integers.
     * This representation consists of a simple <code>value</code> attribute
     * holding its textual representation.
     * 
     * @see #valueOf(CharSequence)
     * @see #toText
     */
    protected static final XmlFormat LARGE_INTEGER_XML = new XmlFormat(LargeInteger.class) {
        public void format(Object obj, XmlElement xml) {
            xml.setAttribute("value", ((LargeInteger) obj).toText());
        }

        public Object parse(XmlElement xml) {
            return LargeInteger.valueOf(xml.getAttribute("value"));
        }
    };

    /**
     * Holds the large integer factories.
     */
    private static final LargeIntegerFactory[] FACTORIES;
    static {
        FACTORIES = new LargeIntegerFactory[28];
        for (int i = 0; i < FACTORIES.length; i++) {
            FACTORIES[i] = new LargeIntegerFactory(ArrayPool.MIN_LENGTH << i);
        }
    }

    /**
     * The large integer representing the additive identity.
     */
    public static final LargeInteger ZERO = (LargeInteger) LargeInteger
            .valueOf(0).moveHeap();

    /**
     * The large integer representing the multiplicative identity.
     */
    public static final LargeInteger ONE = (LargeInteger) LargeInteger.valueOf(
            1).moveHeap();

    /**
     * Indicates if this large integer is negative.
     */
    private boolean _isNegative;

    /**
     * The size of this large integer in words. 
     * The most significand word different from 0 is at index: _size-1
     */
    private int _size;

    /**
     * This large integer positive words (63 bits). 
     * Least significant word first (index 0).
     */
    private long[] _words;

    /**
     * Holds the remainder after a {@link #divide} operation.
     */
    private LargeInteger _remainder;

    /**
     * Base constructor.
     * 
     * @param words the words buffer or <code>null</code> for external 
     *        allocations.
     */
    private LargeInteger(long[] words) {
        _words = words;
    }
    
    /**
     * Returns the large integer of specified <code>long</code> value.
     * 
     * @param  value the <code>long</code> value.
     * @return the corresponding large integernumber.
     */
    public static LargeInteger valueOf(long value) {
        if (value >= 0) {
            LargeInteger z = newInstance(1);
            z._isNegative = false;
            z._size = (value == 0) ? 0 : 1;
            z._words[0] = value;
            return z;
        } else {
            if (value != Long.MIN_VALUE) {
                LargeInteger z = newInstance(1);
                z._isNegative = true;
                z._size = 1;
                z._words[0] = -value;
                return z;
            } else { // Negative would overflow.
                LargeInteger z = newInstance(2);
                z._isNegative = true;
                z._size = 2;
                z._words[0] = 0;
                z._words[1] = 1;
                return z;
            }
        }
    }

    /**
     * Returns the large integer of specified two's-complement binary
     * representation. The input array is assumed to be in <i>big-endian</i>
     * byte-order: the most significant byte is at the offset position.
     * 
     * <p>Note: This representation is consitent with <code>java.lang.BigInteger
     *          </code> byte array representation and can be used for conversion 
     *          between the two classes.</p>
     * 
     * @param  bytes the binary representation (two's-complement).
     * @param  offset the offset at which to start reading the bytes.
     * @param  length the maximum number of bytes to read.
     * @return the corresponding large integer number.
     * @throws IndexOutOfBoundsException 
     *         if <code>offset + length > bytes.length</code>  
     * @see    #toByteArray
     */
    public static LargeInteger valueOf(byte[] bytes, int offset, int length) {
        // Ensures result is large enough (takes into account potential
        // extra bits during negative to positive conversion).
        LargeInteger z = newInstance(((length * 8 + 1) / 63) + 1);
        final boolean isNegative = bytes[offset] < 0;
        int wordIndex = 0;
        int bitIndex = 0;
        z._words[0] = 0;
        for (int i = offset + length; i > offset; bitIndex += 8) {
            long bits = (isNegative ? ~bytes[--i] : bytes[--i]) & MASK_8;
            if (bitIndex < 63 - 8) {
                z._words[wordIndex] |= bits << bitIndex;
            } else { // End of word reached.
                z._words[wordIndex] |= (bits << bitIndex) & MASK_63;
                bitIndex -= 63; // In range [-8..-1]
                z._words[++wordIndex] = bits >> -bitIndex;
            }
        }
        // Calculates size.
        while (z._words[wordIndex] == 0) {
            if (--wordIndex < 0) {
                break;
            }
        }
        z._size = wordIndex + 1;
        z._isNegative = isNegative;

        // Converts one's-complement to two's-complement if negative.
        if (isNegative) { // Adds ONE.
            if (z._size > 0) {
                z._size = add(z._words, z._size, ONE._words, 1, z._words);
            } else {
                z._size = add(ONE._words, 1, z._words, 0, z._words);
            }
        }
        return z;
    }

    /**
     * Returns the large integer for the specified character sequence in
     * decimal number.
     * 
     * @param chars the character sequence.
     * @return {@link #valueOf(CharSequence, int) valueOf(chars, 10)}
     */
    public static LargeInteger valueOf(CharSequence chars) {
        return valueOf(chars, 10);
    }

    /**
     * Returns the large integer for the specified character sequence stated
     * in the specified radix. The characters must all be digits of the
     * specified radix, except the first character which may be a plus sign
     * <code>'+'</code> or a minus sign <code>'-'</code>.
     * 
     * @param chars the character sequence to parse.
     * @param radix the radix to be used while parsing.
     * @return the corresponding large integer.
     * @throws NumberFormatException if the specified character sequence does
     *         not contain a parsable large integer.
     */
    public static LargeInteger valueOf(CharSequence chars, int radix) {
        try {
            final int charsLength = chars.length();
            final boolean isNegative = (chars.charAt(0) == '-') ? true : false;
            int i = (isNegative || (chars.charAt(0) == '+')) ? 1 : 0;

            // Ensures capacity large enough.
            // Bits per character: log2(radix) between 1 and 6 (radix 2 to 36)
            // Most often around 4 (radix 10, 16).
            // Use maximum value of 6 with a division by 64 instead of 63.
            // Adds extra word for potential carry in radix multiplication.
            LargeInteger z = newInstance(((charsLength * 6) >> 6) + 2);

            // z = digit
            z._isNegative = isNegative;
            z._size = chars.charAt(i) == '0' ? 0 : 1;
            z._words[0] = Character.digit(chars.charAt(i), radix);
            LargeInteger digit = LargeInteger.valueOf(0xFF);
            while (++i < charsLength) {
                // z *= radix
                z._size = multiply(z._words, z._size, radix, z._words, 0);
                // z += digit
                digit._words[0] = Character.digit(chars.charAt(i), radix);
                if (z._size > 0) {
                    z._size = add(z._words, z._size, digit._words, 1, z._words);
                } else {
                    z._size = add(digit._words, 1, z._words, 0, z._words);
                }
            }
            digit.recycle();
            return z;
        } catch (IndexOutOfBoundsException e) {
            throw new NumberFormatException("For input characters: \""
                    + chars.toString() + "\"");
        }
    }

    /**
     * Returns the large integer corresponding to the specified 
     * <code>BigInteger</code> instance.
     * 
     * @param  bigInteger the big integer instance.
     * @return the large integer having the same value.
     */
    public static LargeInteger valueOf(java.math.BigInteger bigInteger) {
        byte[] bytes = bigInteger.toByteArray();
        return LargeInteger.valueOf(bytes, 0, bytes.length);
    }


    /**
     * Indicates if this large integer is greater than {@link #ZERO}
     * ({@link #ZERO}is not included).
     * 
     * @return <code>this > ZERO</code>
     */
    public boolean isPositive() {
        return !_isNegative && (_size != 0);
    }

    /**
     * Indicates if this large integer is less than {@link #ZERO}.
     * 
     * @return <code>this < ZERO</code>
     */
    public boolean isNegative() {
        return _isNegative;
    }

    /**
     * Indicates if this large integer is equal to {@link #ZERO}.
     * 
     * @return <code>this == ZERO</code>
     */
    public boolean isZero() {
        return _size == 0;
    }

    /**
     * Returns the final undivided part after division that is less or of 
     * lower degree than the divisor. This value is only set by the 
     * {@link #divide} operation and is not considered as part of 
     * this large integer (ignored by all methods).
     * 
     * @return the remainder of the division for which this large integer
     *         is the quotient.
     */
    public LargeInteger getRemainder() {
        return _remainder;
    }

    /**
     * Indicates if this large integer is larger than the one
     * specified in absolute value.
     * 
     * @param that the integer to be compared with.
     * @return <code>this.abs().compareTo(that.abs()) > 0</code>.
     */
    public boolean isLargerThan(LargeInteger that) {
        return (this._size > that._size)
                || ((this._size == that._size) && compare(this._words,
                        that._words, this._size) > 0);
    }

    /**
     * Returns the two's-complement binary representation of this 
     * large integer. The output array is in <i>big-endian</i>
     * byte-order: the most significant byte is at the offset position.
     * 
     * <p>Note: This representation is consitent with <code>java.lang.BigInteger
     *          </code> byte array representation and can be used for conversion 
     *          between the two classes.</p>
     * 
     * @param  bytes the bytes to hold the binary representation 
     *         (two's-complement) of this large integer.
     * @param  offset the offset at which to start writing the bytes.
     * @return the number of bytes written.
     * @throws IndexOutOfBoundsException 
     *         if <code>bytes.length < (bitLength() >> 3) + 1</code>  
     * @see    #valueOf(byte[], int, int)
     * @see    #bitLength
     */
    public int toByteArray(byte[] bytes, int offset) {
        int bytesLength = (bitLength() >> 3) + 1;
        int wordIndex = 0;
        int bitIndex = 0;
        if (_isNegative) {
            long word = _words[0] - 1;
            long borrow = word >> 63; // -1 if borrow
            word = ~word & MASK_63;
            for (int i = bytesLength + offset; i > offset; bitIndex += 8) {
                if (bitIndex < 63 - 8) {
                    bytes[--i] = (byte) word;
                    word >>= 8;
                } else { // End of word reached.
                    byte bits = (byte) word;
                    word = (++wordIndex < _size)
                            ? _words[wordIndex] + borrow
                            : borrow;
                    borrow = word >> 63; // -1 if borrow
                    word = ~word & MASK_63;
                    bitIndex -= 63; // In range [-8..-1]
                    bytes[--i] = (byte) ((word << -bitIndex) | bits);
                    word >>= (8 + bitIndex);
                }
            }
        } else {
            if (_size != 0) {
                long word = _words[0];
                for (int i = bytesLength + offset; i > offset; bitIndex += 8) {
                    if (bitIndex < 63 - 8) {
                        bytes[--i] = (byte) word;
                        word >>= 8;
                    } else { // End of word reached.
                        byte bits = (byte) word;
                        word = (++wordIndex < _size) ? _words[wordIndex] : 0;
                        bitIndex -= 63; // In range [-8..-1]
                        bytes[--i] = (byte) ((word << -bitIndex) | bits);
                        word >>= (8 + bitIndex);
                    }
                }
            } else { // ZERO
                bytes[offset] = 0;
            }
        }
        return bytesLength;
    }

    /**
     * Returns the minimal number of bits to represent this large integer
     * in the minimal two's-complement (sign excluded).
     * 
     * @return the length of this integer in bits (sign excluded).
     */
    public int bitLength() {
        if (_size != 0) {
            int bitLength = 0;
            long word = _words[_size - 1];
            if (word >= 1L << 32) {
                word >>= 32;
                bitLength += 32;
            }
            if (word >= 1L << 16) {
                word >>= 16;
                bitLength += 16;
            }
            if (word >= 1L << 8) {
                word >>= 8;
                bitLength += 8;
            }
            if (word >= 1L << 4) {
                word >>= 4;
                bitLength += 4;
            }
            bitLength += BIT_LENGTH[(int) word]; // word is now 4 bits long.
            if (_isNegative) { // Correct for exact power of 2 (one less bit).
                int i = _size - 1;
                // _words[i] is always different from 0 and bitLength >= 1
                boolean isPow2 = (_words[i] == (1L << (bitLength - 1)));
                while (isPow2 && (i > 0)) {
                    isPow2 = _words[--i] == 0L;
                }
                if (isPow2) {
                    bitLength--;
                }
            }
            return bitLength + (_size - 1) * 63;
        } else {
            return 0;
        }
    }

    private static final int BIT_LENGTH[] = new int[]{0, 1, 2, 2, 3, 3, 3, 3,
            4, 4, 4, 4, 4, 4, 4, 4};

    /**
     * Returns the negation of this large integer.
     * 
     * @return <code>-this</code>.
     */
    public LargeInteger negate() {
        LargeInteger z = copy();
        z._isNegative = (!_isNegative) && (_size != 0); // -0 = 0
        return z;
    }

    /**
     * Returns the sum of this large integer with the one specified.
     * 
     * @param that the integer to be added.
     * @return <code>this + that</code>.
     */
    public LargeInteger add(LargeInteger that) {
        if (this._isNegative == that._isNegative) {
            if (this._size >= that._size) {
                LargeInteger z = newInstance(this._size + 1);
                z._size = add(this._words, this._size, that._words, that._size,
                        z._words);
                z._isNegative = _isNegative;
                return z;
            } else {
                LargeInteger z = newInstance(that._size + 1);
                z._size = add(that._words, that._size, this._words, this._size,
                        z._words);
                z._isNegative = _isNegative;
                return z;
            }
        } else { // Different signs, equivalent to subtraction.
            // Subtracts smallest to largest (absolute value)
            if (this.isLargerThan(that)) { // this.abs() > that.abs()
                LargeInteger z = newInstance(this._size);
                z._size = subtract(this._words, this._size, that._words,
                        that._size, z._words);
                z._isNegative = this._isNegative;
                return z;
            } else { // that.abs() >= this.abs()
                LargeInteger z = newInstance(that._size);
                z._size = subtract(that._words, that._size, this._words,
                        this._size, z._words);
                z._isNegative = that._isNegative && (z._size != 0);
                return z;
            }
        }
    }

    /**
     * Returns the difference between this large integer and the one
     * specified.
     * 
     * @param that the integer to be subtracted.
     * @return <code>this - that</code>.
     */
    public LargeInteger subtract(LargeInteger that) {
        if (this._isNegative == that._isNegative) {
            // Subtracts smallest to largest (absolute value)
            if (this.isLargerThan(that)) { // this.abs() > that.abs()
                LargeInteger z = newInstance(this._size);
                z._size = subtract(this._words, this._size, that._words,
                        that._size, z._words);
                z._isNegative = this._isNegative;
                return z;
            } else { // that.abs() >= this.abs()
                LargeInteger z = newInstance(that._size);
                z._size = subtract(that._words, that._size, this._words,
                        this._size, z._words);
                z._isNegative = !that._isNegative && (z._size != 0);
                return z;
            }
        } else { // Different signs, equivalent to addition.
            if (this._size >= that._size) {
                LargeInteger z = newInstance(this._size + 1);
                z._size = add(this._words, this._size, that._words, that._size,
                        z._words);
                z._isNegative = _isNegative;
                return z;
            } else {
                LargeInteger z = newInstance(that._size + 1);
                z._size = add(that._words, that._size, this._words, this._size,
                        z._words);
                z._isNegative = _isNegative;
                return z;
            }
        }
    }

    /**
     * Returns the product of this large integer with the specified 
     * <code>long</code>.
     * 
     * @param l the <code>long</code> multiplier.
     * @return <code>this * l</code>.
     */
    public LargeInteger multiply(long l) {
        if (this._size != 0) {
            if (l > 0) {
                LargeInteger z = newInstance(_size + 1);
                z._size = multiply(_words, _size, l, z._words, 0);
                z._isNegative = _isNegative;
                return z;
            } else if (l == Long.MIN_VALUE) { // Avoids -l overflow.
                LargeInteger z = this.shiftLeft(63);
                z._isNegative = !_isNegative;
                return z;
            } else if (l < 0) {
                LargeInteger z = newInstance(_size + 1);
                z._size = multiply(_words, _size, -l, z._words, 0);
                z._isNegative = !_isNegative;
                return z;
            }
        }
        return ZERO;
    }

    /**
     * Returns the product of this large integer with the one specified.
     * 
     * @param that the large integer multiplier.
     * @return <code>this * that</code>.
     */
    public LargeInteger multiply(LargeInteger that) {
        if (this._size >= that._size) {
            if (that._size <= 1) {
                return multiply(that.longValue());
            } else if (that._size < 30) { // Conventional multiplication.
                LargeInteger z = newInstance(this._size + that._size);
                z._size = (this._size >= that._size)
                        ? multiply(this._words, this._size, that._words,
                                that._size, z._words)
                        : multiply(that._words, that._size, this._words,
                                this._size, z._words);
                z._isNegative = this._isNegative != that._isNegative;
                return z;
            } else { // Concurrent Karatsuba multiplication.
                int bitLength = this.bitLength();
                int n = (bitLength >> 1) + (bitLength & 1);
                FastMap results = FastMap.newInstance(3);
                ConcurrentContext.enter();
                try { // this = a + 2^n b,   that = c + 2^n d
                    LargeInteger b = this.shiftRight(n);
                    LargeInteger a = this.subtract(b.shiftLeft(n));
                    LargeInteger d = that.shiftRight(n);
                    LargeInteger c = that.subtract(d.shiftLeft(n));
                    ConcurrentContext.execute(MULTIPLY, a, c, "ac", results);
                    ConcurrentContext.execute(MULTIPLY, b, d, "bd", results);
                    ConcurrentContext.execute(MULTIPLY, a.add(b), c.add(d),
                            "abcd", results);
                } finally {
                    ConcurrentContext.exit();
                }
                LargeInteger ac = (LargeInteger) results.get("ac");
                LargeInteger bd = (LargeInteger) results.get("bd");
                LargeInteger abcd = (LargeInteger) results.get("abcd");
                return ac.add(abcd.subtract(ac).subtract(bd).shiftLeft(n)).add(
                        bd.shiftLeft(2 * n));
            }
        } else {
            return that.multiply(this);
        }
    }

    private static final Logic MULTIPLY = new Logic() {

        public void run(Object[] args) {
            LargeInteger left = (LargeInteger) args[0];
            LargeInteger right = (LargeInteger) args[1];
            FastMap results = (FastMap) args[3];
            LargeInteger product = left.multiply(right); // Recursive.
            synchronized (results) {
                results.put(args[2], product.export());
            }
        }
    };

    /**
     * Returns this large integer divided by the specified <code>int</code>.
     * The remainder of this division is accessible using {@link #getRemainder}. 
     * 
     * @param i the <code>int</code> divisor.
     * @return <code>this / i</code> and <code>this % i</code>
     *        ({@link #getRemainder})
     * @throws ArithmeticException if <code>i == 0</code>
     */
    public LargeInteger divide(int i) {
        if (i != 0) {
            if (this._size != 0) {
                if (i > 0) {
                    LargeInteger z = newInstance(_size);
                    long rem = divide(_words, _size, i, z._words);
                    z._size = (z._words[_size - 1] == 0L) ? _size - 1 : _size;
                    z._isNegative = _isNegative && (z._size != 0);
                    z._remainder = valueOf(_isNegative ? -rem : rem);
                    return z;
                } else if (i == Integer.MIN_VALUE) { // Negative would overflow.
                    LargeInteger z = this.abs().shiftRight(31);
                    z._isNegative = !_isNegative && (z._size != 0);
                    z._remainder = _isNegative
                            ? valueOf(-(_words[0] & MASK_31))
                            : valueOf(_words[0] & MASK_31);
                    return z;
                } else { // i < 0
                    LargeInteger z = this.divide(-i);
                    z._isNegative = !_isNegative && (z._size != 0);
                    return z;
                }
            } else { // Zero. 
                LargeInteger zero = valueOf(0);
                zero._remainder = ZERO;
                return zero;
            }
        } else {
            throw new ArithmeticException("Division by zero");
        }
    }

    /**
     * Returns this large integer divided by the one specified (integer
     * division). This operation is independant from the current modulo (unlike
     * {@link #reciprocal}).
     * The remainder of this division is accessible using {@link #getRemainder}. 
     * 
     * @param that the integer divisor.
     * @return <code>this / that</code> and <code>this % that</code> 
     *        ({@link #getRemainder})
     * @throws ArithmeticException if <code>that.equals(ZERO)</code>
     */
    public LargeInteger divide(LargeInteger that) {
        if ((that._size <= 1) && (that.bitLength() <= 31)) {
            return divide(that.intValue());
        } else {
            PoolContext.enter();
            try {
                LargeInteger thisAbs = this.abs();
                LargeInteger thatAbs = that.abs();
                int precision = thisAbs.bitLength() - thatAbs.bitLength() + 1;
                if (precision <= 0) {
                    LargeInteger result = LargeInteger.valueOf(0);
                    result._remainder = this;
                    return (LargeInteger) result.export();
                }
                LargeInteger thatReciprocal = thatAbs.inverseScaled(precision);
                LargeInteger result = thisAbs.multiply(thatReciprocal);
                result = result.shiftRight(thisAbs.bitLength() + 1);

                // Calculates remainder, corrects for result +/- 1 error. 
                LargeInteger remainder = thisAbs.subtract(thatAbs
                        .multiply(result));
                if (remainder.compareTo(thatAbs) >= 0) {
                    remainder = remainder.subtract(thatAbs);
                    result = result.add(ONE);
                } else if (remainder.isNegative()) {
                    remainder = remainder.add(thatAbs);
                    result = result.subtract(ONE);
                }

                // Sets signs for result and remainder.
                remainder._isNegative = this._isNegative
                        && (remainder._size != 0);
                result._isNegative = (this._isNegative != that._isNegative)
                        && (result._size != 0);
                result._remainder = (LargeInteger) remainder.export();
                return (LargeInteger) result.export();

            } finally {
                PoolContext.exit();
            }
        }
    }

    /**
     * Returns a scaled approximation of <code>1 / this</code>.
     * 
     * @param precision the requested precision (reciprocal error being ± 1).
     * @return <code>2<sup>(precision + this.bitLength())</sup> / this</code>
     * @throws ArithmeticException if <code>this.isZero()</code>
     */
    public LargeInteger inverseScaled(int precision) {
        // todo   Use faster square() method, shift on place.
        if (precision <= 31) { // Straight calculation.
            long divisor = this.shiftRight(this.bitLength() - precision)._words[0];
            long dividend = 1L << precision * 2;
            return (this.isNegative()) ? LargeInteger.valueOf(-dividend
                    / divisor) : LargeInteger.valueOf(dividend / divisor);
        } else { // Newton iteration (x = 2 * x - x^2 * this).
            LargeInteger x = inverseScaled(precision / 2 + 1); // Estimate.
            LargeInteger thisTrunc = shiftRight(bitLength() - (precision + 2));
            LargeInteger prod = thisTrunc.multiply(x).multiply(x);
            int diff = 2 * (precision / 2 + 2);
            LargeInteger prodTrunc = prod.shiftRight(diff);
            LargeInteger xPad = x.shiftLeft(precision - precision / 2 - 1);
            return xPad.add(xPad.subtract(prodTrunc));
        }
    }

    /**
     * Returns this large integer modulo the specified large integer. 
     * This method always returns a positive number.
     * 
     * @param m the modulus.
     * @return <code>this mod m</code>
     * @throws ArithmeticException if <code>!m.isPositive()</code>
     */
    public LargeInteger mod(LargeInteger m) {
        if (m.isPositive()) {
            if (!this.isNegative() && (this.compareTo(m) < 0)) {
                return this; // Shortcut.
            } else {
                LargeInteger result = this.divide(m).getRemainder();
                return (result.isNegative()) ? result.add(m) : result;
            }
        } else {
            throw new ArithmeticException("Modulus: " + m + " is not positive");
        }
    }

    /**
     * Returns the greatest common divisor of this large integer and 
     * the one specified.
     * 
     * @param  that the other number to compute the GCD with.
     * @return a positive number or {@link #ZERO} if
     *         <code>(this.isZero() && that.isZero())</code>.
     */
    public LargeInteger gcd(LargeInteger that) {
        // To optimize using binary algorithm 
        // http://www.cut-the-knot.org/blue/binary.shtml
        LargeInteger a = this.abs();
        LargeInteger b = that.abs();
        while (!b.isZero()) {
            LargeInteger tmp = a.divide(b);
            LargeInteger c = tmp.getRemainder();
            tmp.recycle();
            a.recycle();
            a = b;
            b = c;
        }
        return a;
    }

    /**
     * Returns the absolute value of this large integer.
     * 
     * @return <code>abs(this)</code>.
     */
    public LargeInteger abs() {
        return (isPositive() || isZero()) ? this : this.negate();
    }

    /**
     * Returns the value of this large integer after performing a binary
     * shift to left. The shift distance, <code>n</code>, may be negative,
     * in which case this method performs a right shift.
     * 
     * @param n the shift distance, in bits.
     * @return <code>this &lt;&lt; n</code>.
     * @see #shiftRight
     */
    public LargeInteger shiftLeft(int n) {
        if (n >= 0) {
            if (_size != 0) {
                final int wordShift = n < 63 ? 0 : n / 63;
                final int bitShift = n - wordShift * 63;
                LargeInteger z = newInstance(_size + wordShift + 1);
                z._isNegative = _isNegative;
                z._size = shiftLeft(wordShift, bitShift, _words, _size,
                        z._words);
                return z;
            } else { // ZERO << n == ZERO
                return this;
            }
        } else {
            return shiftRight(-n);
        }
    }

    /**
     * Returns the value of this large integer after performing a binary
     * shift to right with sign extension <code>(-1 >> 1 == -1)</code>.
     * The shift distance, <code>n</code>, may be negative, in which case 
     * this method performs a left shift.
     * 
     * @param n the shift distance, in bits.
     * @return <code>this &gt;&gt; n</code>.
     * @see    #shiftLeft
     */
    public LargeInteger shiftRight(int n) {
        if (n >= 0) {
            final int wordShift = n < 63 ? 0 : n / 63;
            final int bitShift = n - wordShift * 63;
            if (_size > wordShift) {
                LargeInteger z = newInstance(_size - wordShift);
                z._size = shiftRight(wordShift, bitShift, _words, _size,
                        z._words);
                z._isNegative = _isNegative;
                if (_isNegative) { // Adjust, two's-complement is being shifted.
                    int i = wordShift;
                    boolean bitsLost = (bitShift != 0)
                            && (_words[i] << (64 - bitShift)) != 0;
                    while ((!bitsLost) && --i >= 0) {
                        bitsLost = _words[i--] != 0;
                    }
                    if (bitsLost) { // Adds ONE to result.
                        if (z._size > 0) {
                            z._size = add(z._words, z._size, ONE._words, 1,
                                    z._words);
                        } else {
                            z._size = add(ONE._words, 1, z._words, 0, z._words);
                        }
                    }
                }
                return z;
            } else { // All bits have been shifted.
                return _isNegative ? valueOf(-1) : valueOf(0);
            }
        } else {
            return shiftLeft(-n);
        }
    }

    /**
     * Returns the product of this large integer with specified power of 10.
     * For example:<pre>
     *     LargeInteger billion = LargeInteger.ONE.E(9); // 1E9
     *     LargeInteger million = billion.E(-3);</pre>
     *
     * @param n the decimal exponent.
     * @return <code>this * 10<sup>n</sup></code>
     */
    public LargeInteger E(int n) {
        if (this._size == 0) {
            return ZERO;
        } else if (n > 0) {
            int eBitLength = (int) (n * DIGITS_TO_BITS);
            LargeInteger z = newInstance(_size + (eBitLength / 63) + 1);
            z._isNegative = _isNegative;
            // Multiplies by 5^n
            z._size = multiply(_words, _size, LONG_POW_5[n % LONG_MAX_E],
                    z._words, 0);
            for (int i = n / LONG_MAX_E; i > 0; i--) {
                z._size = multiply(z._words, z._size, LONG_POW_5[LONG_MAX_E],
                        z._words, 0);
            }
            // Multiplies by 2^n
            final int wordShift = n < 63 ? 0 : n / 63;
            final int bitShift = n - wordShift * 63;
            z._size = shiftLeft(wordShift, bitShift, z._words, z._size,
                    z._words);
            return z;
        } else if (n < 0) {
            n = -n;
            // Divides by 2^n
            final int wordShift = n < 63 ? 0 : n / 63;
            final int bitShift = n - wordShift * 63;
            if (_size <= wordShift) { // All bits would be shifted. 
                return ZERO;
            }
            LargeInteger z = newInstance(_size - wordShift);
            z._size = shiftRight(wordShift, bitShift, _words, _size, z._words);
            // Divides by 5^n
            divide(z._words, z._size, INT_POW_5[n % INT_MAX_E], z._words);
            z._size = ((z._size > 0) && (z._words[z._size - 1] == 0L))
                    ? z._size - 1
                    : z._size;
            for (int i = n / INT_MAX_E; i > 0; i--) {
                divide(z._words, z._size, INT_POW_5[INT_MAX_E], z._words);
                z._size = ((z._size > 0) && (z._words[z._size - 1] == 0L))
                        ? z._size - 1
                        : z._size;
            }
            z._isNegative = _isNegative && (z._size != 0);
            return z;
        } else { // n = 0
            return this;
        }
    }

    private static double DIGITS_TO_BITS = MathLib.LOG10 / MathLib.LOG2;

    private static int[] INT_POW_5 = new int[]{1, 5, 25, 125, 625, 3125, 15625,
            78125, 390625, 1953125, 9765625, 48828125, 244140625, 1220703125};

    private static int INT_MAX_E = INT_POW_5.length - 1;

    private static long[] LONG_POW_5 = new long[]{1L, 5L, 25L, 125L, 625L,
            3125L, 15625L, 78125L, 390625L, 1953125L, 9765625L, 48828125L,
            244140625L, 1220703125L, 6103515625L, 30517578125L, 152587890625L,
            762939453125L, 3814697265625L, 19073486328125L, 95367431640625L,
            476837158203125L, 2384185791015625L, 11920928955078125L,
            59604644775390625L, 298023223876953125L, 1490116119384765625L,
            7450580596923828125L};

    private static int LONG_MAX_E = LONG_POW_5.length - 1;

    /**
     * Returns the decimal text representation of this number.
     *
     * @return the text representation of this number.
     */
    public Text toText() {
        try {
            TextBuilder tb = TextBuilder.newInstance();
            appendTo(tb, 10);
            return tb.toText();
        } catch (IOException ioError) {
            throw new InternalError(); // Should never get there.
        }
    }

    /**
     * Returns the text representation of this number in the specified radix.
     *
     * @param radix the radix of the representation.
     * @return the text representation of this number.
     */
    public Text toText(int radix) {
        try {
            TextBuilder tb = TextBuilder.newInstance();
            appendTo(tb, radix);
            return tb.toText();
        } catch (IOException ioError) {
            throw new InternalError(); // Should never get there.
        }
    }

    /**
     * Compares this large integer against the specified object.
     * 
     * @param that the object to compare with.
     * @return <code>true</code> if the objects are the same; <code>false</code>
     *         otherwise.
     */
    public boolean equals(Object that) {
        if (that instanceof LargeInteger) {
            LargeInteger x = (LargeInteger) that;
            return (_size == x._size) && (_isNegative == x._isNegative)
                    && (compare(_words, x._words, _size) == 0);
        } else {
            return false;
        }
    }

    /**
     * Returns the hash code for this large integer number.
     * 
     * @return the hash code value.
     */
    public int hashCode() {
        long code = 0;
        for (int i = _size - 1; i >= 0; i--) {
            code = code * 31 + _words[i];
        }
        return _isNegative ? -(int) code : (int) code;
    }

    /**
     * Returns the low order bits of this large integer as 
     * an <code>int</code>.
     * 
     * <p>Note: This conversion can lose information about the overall magnitude
     *          of the integer value and may return a result with the opposite 
     *          sign.</p>
     * 
     * @return the numeric value represented by this integer after conversion
     *         to type <code>int</code>.
     */
    public int intValue() {
        return (int) longValue();
    }

    /**
     * Returns the low order bits of this large integer as 
     * a <code>long</code>.
     * 
     * <p>Note: This conversion can lose information about the overall magnitude
     *          of the integer value and may return a result with the opposite 
     *          sign.</p>
     * 
     * @return the numeric value represented by this integer after conversion
     *         to type <code>long</code>.
     */
    public long longValue() {
        if (_size == 0) {
            return 0;
        } else if (_size == 1) {
            return _isNegative ? -_words[0] : _words[0];
        } else {
            return _isNegative
                    ? -((_words[1] << 63) | _words[0])
                    : (_words[1] << 63) | _words[0];
        }
    }

    /**
     * Returns the value of this large integer as a <code>float</code>.
     * 
     * @return the numeric value represented by this integer after conversion
     *         to type <code>float</code>.
     */
    public float floatValue() {
        return (float) doubleValue();
    }

    /**
     * Returns the value of this large integeras a <code>double</code>.
     * 
     * @return the numeric value represented by this integer after conversion
     *         to type <code>double</code>.
     */
    public double doubleValue() {
        if (_size == 0) {
            return 0.0;
        } else if (_size == 1) {
            return _isNegative ? -_words[0] : _words[0];
        } else { // size >= 2
            double absValue = ((_words[_size - 1] * TWO_POW63) + _words[_size - 2])
                    * MathLib.pow(TWO_POW63, _size - 2);
            return _isNegative ? -absValue : absValue;
        }
    }

    private static final double TWO_POW63 = 9223372036854775808.0;

    /**
     * Compares two large integer numerically.
     * 
     * @param  that the integer to compare with.
     * @return -1, 0 or 1 as this integer is numerically less than, equal to,
     *         or greater than <code>that</code>.
     * @throws ClassCastException <code>that</code> is not a 
     *         large integer.
     */
    public int compareTo(Object that) {
        LargeInteger x = (LargeInteger) that;
        // Compares sign.
        if (_isNegative && !x._isNegative) {
            return -1;
        } else if (!_isNegative && x._isNegative) {
            return 1;
        } else { // Same sign.
            // Compares size.
            if (_size > x._size) {
                return _isNegative ? -1 : 1;
            } else if (x._size > _size) {
                return _isNegative ? 1 : -1;
            } else { // Same size.
                return _isNegative
                        ? compare(x._words, _words, _size)
                        : compare(_words, x._words, _size);
            }
        }
    }

    /**
     * Returns a copy of this large integer allocated in the current context.
     *
     * @return a copy of this large integer. 
     */
    private LargeInteger copy() {
        LargeInteger x = newInstance(_size);
        x._isNegative = _isNegative;
        x._size = _size;
        copy(_words, x._words, _size);
        return x;
    }

    /**
     * Returns the {@link javolution.realtime.LocalContext local} modulus for modular
     * arithmetic (used by {@link Operable} operations only). If the modulus is
     * not set then the {@link #reciprocal} operation raises 
     * <code>IllegalStateException</code>.
     * 
     * @return the local modulus or <code>null</code> if unset.
     * @see #setModulus
     */
    public static LargeInteger getModulus() {
        return (LargeInteger) MODULUS.getValue();
    }
    private static final Variable MODULUS = new Variable();

    /**
     * Sets the context local modulus for modular arithmetic (used by
     * {@link Operable} operations only). If the modulus is not set 
     * the {@link #reciprocal} operation raises 
     * <code>IllegalStateException</code>.
     * 
     * @param modulus the new modulus or <code>null</code> to unset the modulus.
     * @throws IllegalArgumentException if <code>modulus <= 0</code>
     */
    public static void setModulus(LargeInteger modulus) {
        if ((modulus == null) || modulus.isPositive()) {
            MODULUS.setValue(modulus);
        } else {
            throw new IllegalArgumentException("modulus: " + modulus
                    + " is not greater than 0");
        }
    }


    // Implements Operable.
    public Operable plus(Operable that) {
        LargeInteger modulus = (LargeInteger) MODULUS.getValue();
        if (modulus != null) {
            LargeInteger result = this.mod(modulus).add(
                    ((LargeInteger) that).mod(modulus));
            return (result.compareTo(modulus) < 0) ? result : result
                    .subtract(modulus);

        } else {
            return this.add((LargeInteger) that);
        }
    }

    // Implements Operable.
    public Operable opposite() {
        LargeInteger modulus = (LargeInteger) MODULUS.getValue();
        if (modulus != null) {
            return modulus.subtract(this.mod(modulus));
        } else {
            return this.negate();
        }
    }

    // Implements Operable.
    public Operable times(Operable that) {
        LargeInteger modulus = (LargeInteger) MODULUS.getValue();
        if (modulus != null) {
            return this.multiply((LargeInteger) that).mod(modulus);
        } else {
            return this.multiply((LargeInteger) that);
        }
    }

    /**
     * Returns the modular inverse of this large integer.
     *
     * @return a large integer such as  
     *         <code>this.times(this.reciprocal()) modulo m = ONE</code> 
     *         m being the local modulus.
     * @throws IllegalStateException if the modulus is not set.
     * @see #setModulus
     */
    public Operable reciprocal() {
        LargeInteger modulus = (LargeInteger) MODULUS.getValue();
        if (modulus != null) {
            PoolContext.enter();
            try {
                // Extended Euclidian Algorithm
                LargeInteger a = this;
                LargeInteger b = modulus;
                LargeInteger p = ONE;
                LargeInteger q = ZERO;
                LargeInteger r = ZERO;
                LargeInteger s = ONE;
                while (!b.isZero()) {
                    LargeInteger quot = a.divide(b);
                    LargeInteger c = quot.getRemainder();
                    a = b;
                    b = c;
                    LargeInteger new_r = p.subtract(quot.multiply(r));
                    LargeInteger new_s = q.subtract(quot.multiply(s));
                    p = r;
                    q = s;
                    r = new_r;
                    s = new_s;
                }
                return (LargeInteger) p.mod(modulus).export();
            } finally {
                PoolContext.exit();
            }
        } else {
            throw new IllegalStateException("Modulus is not set");
        }
    }

    /**
     * Appends the text representation of this large integer in
     * the specified radix to the <code>TextBuilder</code> argument.
     *
     * @param tb the <code>Appendable</code> to append.
     * @param radix the radix of the representation.
     * @return the specified <code>Appendable</code>.
     * @throws IOException if an I/O exception occurs.
     */
    TextBuilder appendTo(TextBuilder tb, int radix) throws IOException {
        if (this.isZero()) {
            return tb.append("0");
        } else if (this.isNegative()) {
            tb.append('-');
        }
        PoolContext.enter();
        try {
            int tmpSize = (radix < 8)
                    ? this.bitLength()
                    : (this.bitLength() / 3) + 1;
            ObjectPool tmpPool = ArrayPool.byteArray(tmpSize);
            byte[] tmp = (byte[]) tmpPool.next();
            int tmpIndex = 0;
            LargeInteger x = this.abs();
            while (!x.isZero()) {
                LargeInteger i = x.divide(radix);
                LargeInteger j = i.multiply(radix);
                LargeInteger k = x.subtract(j);
                tmp[tmpIndex++] = k.byteValue();
                // Recycles temporary objects.
                k.recycle();
                j.recycle();
                x.recycle();
                x = i;
            }
            for (int i = tmpIndex; i > 0;) {
                tb.append((Character.forDigit(tmp[--i], radix)));
            }
            tmpPool.recycle(tmp);
        } finally {
            PoolContext.exit();
        }
        return tb;
    }

    ///////////////
    // Utilities //
    ///////////////
    private static final long MASK_63 = 0x7FFFFFFFFFFFFFFFL;

    private static final long MASK_32 = 0xFFFFFFFFL;

    private static final long MASK_31 = 0x7FFFFFFFL;

    private static final long MASK_8 = 0xFFL;

    // Preconditions: xSize >= ySize
    private static int add(long[] x, int xSize, long[] y, int ySize, long[] z) {
        long sum = 0;
        int i = 0;
        while (i < ySize) {
            sum += x[i] + y[i];
            z[i++] = sum & MASK_63;
            sum >>>= 63;
        }
        while ((sum != 0) && (i < xSize)) {
            sum += x[i];
            z[i++] = sum & MASK_63;
            sum >>>= 63;
        }
        if (sum == 0) {
            while (i < xSize) {
                z[i] = x[i++];
            }
            return i;
        } else { // i >= xSize
            z[i++] = sum;
            return i;
        }
    }

    // Preconditions: x >= y.
    private static int subtract(long[] x, int xSize, long[] y, int ySize,
            long[] z) {
        long diff = 0;
        int i = 0;
        while (i < ySize) {
            diff += x[i] - y[i];
            z[i++] = diff & MASK_63;
            diff >>= 63; // Equals to -1 if borrow.
        }
        while (diff != 0) {
            diff += x[i];
            z[i++] = diff & MASK_63;
            diff >>= 63; // Equals to -1 if borrow.
        }
        while (i < xSize) {
            z[i] = x[i++];
        }
        // Calculates size.
        while ((--i >= 0) && (z[i] == 0)) {
        }
        return i + 1;
    }

    private static int compare(long[] left, long[] right, int size) {
        for (int i = size - 1; i >= 0; i--) {
            if (left[i] > right[i]) {
                return 1;
            } else if (left[i] < right[i]) {
                return -1;
            }
        }
        return 0;
    }

    // Preconditions: size > 0
    private static int shiftLeft(int wordShift, int bitShift, long[] words,
            int size, long[] z) {
        final int shiftRight = 63 - bitShift;
        int i = size;
        int j = size + wordShift;
        long tmp = words[--i];
        z[j--] = tmp >>> shiftRight;
        while (i > 0) {
            z[j--] = ((tmp << bitShift) & MASK_63)
                    | ((tmp = words[--i]) >>> shiftRight);
        }
        z[j] = (tmp << bitShift) & MASK_63;
        while (j > 0) {
            z[--j] = 0;
        }
        return (z[size + wordShift] != 0) ? size + wordShift + 1 : size
                + wordShift;
    }

    // Preconditions: size > wordShift 
    private static int shiftRight(int wordShift, int bitShift, long[] words,
            int size, long[] z) {
        final int shiftLeft = 63 - bitShift;
        int i = wordShift;
        int j = 0;
        long tmp = words[i];
        while (i < size - 1) {
            z[j++] = (tmp >>> bitShift) | ((tmp = words[++i]) << shiftLeft)
                    & MASK_63;
        }
        tmp >>>= bitShift;
        z[j] = tmp;
        return (tmp != 0) ? j + 1 : j;
    }

    // Precondition: xSize >= ySize
    private static int multiply(long[] x, int xSize, long[] y, int ySize,
            long[] z) {
        if (ySize != 0) {
            // Calculates z = x * y[0];
            int zSize = multiply(x, xSize, y[0], z, 0);

            // Increment z with shifted products.
            for (int i = 1; i < ySize;) {
                zSize = multiply(x, xSize, y[i], z, i++);
            }
            return zSize;
        }
        return 0; // x * ZERO = ZERO
    }

    // Preconditions: size > 0, z.length > size + shift, k >= 0
    private static int multiply(long[] words, int size, long k, long[] z,
            int shift) {
        long carry = 0; // 63 bits
        long kl = k & MASK_32; // 32 bits.
        long kh = k >> 32; // 31 bits
        for (int i = 0, j = shift; i < size;) {
            long w = words[i++];
            long wl = w & MASK_32; // 32 bits
            long wh = w >> 32; // 31 bits
            // Lower product.
            long tmp = wl * kl; // 64 bits
            long low = (tmp & MASK_63) + carry;
            carry = (tmp >>> 63) + (low >>> 63);

            // Cross product.
            tmp = wl * kh + wh * kl; // 64 bits
            low = (low & MASK_63) + ((tmp << 32) & MASK_63);

            // Calculates new carry  
            carry += ((wh * kh) << 1 | (low >>> 63)) + (tmp >>> 31);

            if (shift != 0) { // Adds to z.
                low = z[j] + (low & MASK_63);
                z[j++] = low & MASK_63;
                carry += low >>> 63;
            } else { // Sets z.
                z[j++] = low & MASK_63;
            }
        }
        z[shift + size] = carry;
        return (carry != 0) ? shift + size + 1 : shift + size;
    }

    // Precondition: y length <= 32 bits.
    // Returns remainder.
    private static long divide(long[] x, int xSize, long y, long[] z) {
        long r = 0;
        for (int i = xSize; i > 0;) {
            long w = x[--i];

            long wh = (r << 31) | (w >>> 32);
            long qh = wh / y;
            r = wh - qh * y;

            long wl = (r << 32) | (w & MASK_32);
            long ql = wl / y;
            r = wl - ql * y;

            z[i] = (qh << 32) | ql;
        }
        return r;
    }

    // Fast copy.
    private static void copy(long[] src, long[] dest, int length) {
        if (length < 32) {
            for (int i = length; i > 0;) {
                dest[--i] = src[i];
            }
        } else {
            System.arraycopy(src, 0, dest, 0, length);
        }
    }

    /**
     * Returns a new instance of mimimum size.
     *
     * @param  size the minimum length of the _words array.
     * @return a new or recycled instance.
     */
    private static LargeInteger newInstance(int size) {
        return (LargeInteger) FACTORIES[ArrayPool.indexFor(size)].object();
    }

    private final static class LargeIntegerFactory extends Factory {

        private final int _size;

        private LargeIntegerFactory(int size) {
            _size = size;
        }

        public Object create() {
            return new LargeInteger(new long[_size]);
        }
    }

    private static final long serialVersionUID = 3761685706208391225L;
}