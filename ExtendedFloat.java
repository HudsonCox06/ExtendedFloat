import java.math.BigInteger;
/**
 * ExtendedFloat is a high-range floating-point type that stores values using a 
 * 1024-bit mantissa (64 bit x 16) and an integer exponent. It supports basic arithmetic,
 * comparisons, conversion to double, and special IEEE-style values (ZERO, INFINITY, NAN).
 * 
 * Most arithmetic operations currently fall back to double for simplicity,
 * while the class structure and mantissa helpers are designed to support a 
 * future full-precision big-float implementation.
 **/
public class ExtendedFloat{ 

	// The amount of 64-bit 'limbs' we use to represent a 1024 bit mantissa
	private static final int NUM_LIMBS = 16;

	// The minimum amount that our integer-represented exponent can reach before underflow occurs
	private static final int MIN_EXP = -100000;

	// The maximum amount that our integer-represented exponent can reach before overflow occurs
	private static final int MAX_EXP = 100000;

	// These are the 4 possible categories for our representation, as well as the invariants for each
	private enum Kind{
		NORMAL,
		/* When kind == NORMAL
		- mantissa.length == NUM_LIMBS
		- mantissa is normalized (top limb has leading 1)
		- exponent is always between MIN_EXP and MAX_EXP
		- mantissa represents a binary fractional value in [1, 2)
		*/

		ZERO,
		/* When kind == ZERO:
		- exponent doesnt matter (set to 0)
		- mantissa must be all zeroes
		- sign is 0
		*/

		INFINITY,
		/* When kind == INFINITY:
		- mantissa ignored
		- exponent ignored
		- sign indicates positive or negative infinity
		*/

		NAN
		/* When kind == NAN:
		- mantissa and exponent irrelevant
		- sign irrelevant
		*/
	}

	
	private static final ExtendedFloat ZERO_CONST = new ExtendedFloat(0.0);
	private static final ExtendedFloat ONE_CONST = new ExtendedFloat(1.0);
	private static final ExtendedFloat NaN_CONST = new ExtendedFloat(Double.NaN);
	private static final ExtendedFloat POSITIVE_INFINITY = new ExtendedFloat(Double.POSITIVE_INFINITY);
	private static final ExtendedFloat NEGATIVE_INFINITY = new ExtendedFloat(Double.NEGATIVE_INFINITY);

	private Kind kind;
	// 0=positive, 1=negative
	private int sign;
	private int exponent;
	private long[] mantissa; // length NUM_LIMBS

	/**
	 * Default Constructor - Represents zero
	 * 
	 * Zero is represented by the following terms:
	 * - All mantissa limbs = 0
	 * - sign = positive (0)
	 * - We ignore the exponent, but it is set to 0 for simplicity
	 **/
	public ExtendedFloat(){
		this.kind = Kind.ZERO;
		this.sign = 0;
		this.exponent = 0;
		this.mantissa = new long[NUM_LIMBS];
	}

	// Constructs an ExtendedFloat from a primitive double
	public ExtendedFloat(double value){
		this.mantissa = new long[NUM_LIMBS];

		// NaN
		if(Double.isNaN(value)){
			this.kind = Kind.NAN;
			this.sign = 0;
			this.exponent = 0;
			return;
		}

		// Infinity
		if(Double.isInfinite(value)){
			this.kind = Kind.INFINITY;
			this.sign = (value < 0 ? 1 : 0);
			this.exponent = 0; // ignored
			// mantissa stays all zeros
			return;
		}

		// Zero 
		if(value == 0.0){
			this.kind = Kind.ZERO;
			this.sign = 0;
			this.exponent = 0;
			return;
		}

		this.kind = Kind.NORMAL;

		// Get raw IEEE 754 bits of the double
		long bits = Double.doubleToRawLongBits(value);

		// Extract sign (0 for positive, 1 for negative)
		this.sign = (int) ((bits >>> 63) & 1L);

		// Get raw exponent bits (11 bits)
		int rawExp = (int) ((bits >>> 52) & 0x7FFL);

		// Get the fraction bits (52 bits)
		long fracBits = bits & 0xFFFFFFFFFFFFFL;

		// Sig = ineger significand (up to 53 bits)
		long Sig;
		// E = unbiased exponent 
		int E;

		if(rawExp == 0){
			// Subnormal number: exponent is fixed, no leading 1
			// value = (-1)^sign * (fracBits / 2^52) * 2^(1 - 1023)
			E = 1 - 1023; // -1022
			Sig = fracBits; // no implicit 1

			// Normalize Sig so that its most significant bit is at position 52
			// (so it looks like a normal significand)
			if(Sig == 0){
				// This should NOT happen because we already handled value == 0,
				// but guard just in case
				this.kind = Kind.ZERO;
				this.exponent = 0;
				return;
			}

			// Find how many leading zeros before the top bit of Sig
			int leadingZeros = Long.numberOfLeadingZeros(Sig);
			// For a 64-bit long, bit index of MSB is (63 - leadingZeros)
			int msbIndex = 63 - leadingZeros; // between 0 and 62

			// We want the MSB at bit positive 52, so shift left:
			int shift = 52 - msbIndex;
			if(shift > 0){
				Sig <<= shift;
				E -= shift; // shifting left increases value, so decrease exponent
			}
		} else{
			// Normal number: rawExp in [1, 2046]
			// value = (-1)^sign * (1 + fracBits/2^52) * 2^(rawExp - 1023)
			E = rawExp - 1023;

			// Significand with implicit leading 1 at bit 52
			Sig = (1L << 52) | fracBits;
		}

		// Total mantissa bits
		final int P = 1024;
		int shift = (P - 1) - 52; // 1023-52 = 971

		/* We conceptually want M = Sig << shift, but M is 1024 bits,
			so we map it directly into mantissa[15]

			mantissa[15] holds bits 960..1023 of M.
			Since shift >= 960, Sig's bits all land in that top limb.

			Offset within mantissa[15] = shift - 960 = 11.
		*/
		int limbIndex = NUM_LIMBS - 1; // 15
		int limbBitOffset = shift - (limbIndex * 64); // 11

		// Clear array (mostly for readability since it was already instantiated to 0)
		for(int i = 0; i < NUM_LIMBS; i++){
			this.mantissa[i] = 0L;
		}

		// Place Sig into the top limb at the correct bit offset
		this.mantissa[limbIndex] = Sig << limbBitOffset;

		// Internal exponent is just the unbiased exponent E
		this.exponent = E;

		// Now enforce exponent bounds: if out of range -> overflow/underflow
		if(this.exponent > MAX_EXP){
			// Overflow -> treat as infinity
			this.kind = Kind.INFINITY;
			// mantissa can stay as is
		} else if(this.exponent < MIN_EXP){
			// Underflow -> treat as zero
			this.kind = Kind.ZERO;
			// leave mantissa and sign
		}

	}

	// Constructor which will clone values from an existing ExtendedFloat object
	public ExtendedFloat(ExtendedFloat copy){
		this.kind = copy.kind;
		this.sign = copy.sign;
		this.exponent = copy.exponent;
		this.mantissa = copy.mantissa.clone();
	}

	// Internal helper method used to directly set each component
	private ExtendedFloat(int sign, int exponent, long[] mantissa){
		this.kind = Kind.NORMAL;
		this.sign = sign;
		this.exponent = exponent;
		this.mantissa = mantissa.clone();
	}

	public static ExtendedFloat valueOf(double value){
		return new ExtendedFloat(value);
	}

	// Adds this ExtendedFloat to another, returning a new ExtendedFloat
	public ExtendedFloat add(ExtendedFloat other){
		// if either is NaN, result is NaN
		if(this.isNaN() || other.isNaN()) return NaN_CONST;

		// Infinity
		if(this.isInfinite() || other.isInfinite()){

			if(this.isInfinite() && other.isInfinite()){
				if(this.sign == other.sign){
					// Both same sign infinity
					return (this.isNegative() ? NEGATIVE_INFINITY : POSITIVE_INFINITY);
				}
				// Opposite sign infinity -> NaN
				return NaN_CONST;
			}

			if(this.isInfinite()){
				// Only this is infinite
				return (this.isNegative() ? NEGATIVE_INFINITY : POSITIVE_INFINITY);
			}

			// Only other is infinite
			return (other.isNegative() ? NEGATIVE_INFINITY : POSITIVE_INFINITY);
		}

		// Zero handling
		if(this.isZero() && other.isZero()) return ZERO_CONST; // both zero

		if(this.isZero()){
			return new ExtendedFloat(other);
		}
		if(other.isZero()){
			return new ExtendedFloat(this);
		}

		// Both are finite, nonzero NORMALS
		// Align exponent: shift the smaller exponent value to match the larger
		int expDiff = this.exponent - other.exponent;

		long[] m1, m2;
		int resultExp;
		int s1 = this.sign;
		int s2 = other.sign;

		if(expDiff > 0){
			// this has larger exponent, shift other's mantissa right
			m1 = this.mantissa.clone();
			m2 = shiftRightMagnitude(other.mantissa, expDiff);
			resultExp = this.exponent;
		} else if(expDiff < 0){
			// other has larger exponent, shift this mantissa right
			m1 = shiftRightMagnitude(this.mantissa, -expDiff);
			m2 = other.mantissa.clone();
			resultExp = other.exponent;
		} else{
			// Same exponent
			m1 = this.mantissa.clone();
			m2 = other.mantissa.clone();
			resultExp = this.exponent;
		}

		// Now perform addition or subtraction based on signs
		long[] resultMantissa;
		int resultSign;
		boolean hadCarryOut = false;

		if(s1 == s2){
			//Same sign -> add magnitudes
			AddResult addResult = addMagnitudeWithCarry(m1, m2);
			resultMantissa = addResult.mantissa;
			hadCarryOut = addResult.carryOut;
			resultSign = s1;
		} else{
			// Different signs; subtract magnitudes
			int cmp = compareMagnitude(m1, m2);
			if(cmp > 0){
				// |this| > |other|
				resultMantissa = subtractMagnitude(m1, m2);
				resultSign = s1;
			} else if(cmp < 0){
				// |other| > |this|
				resultMantissa = subtractMagnitude(m2, m1);
				resultSign = s2;
			} else{
				// Equal magnitudes, opposite signs -> zero
				return ZERO_CONST;
			}
		}

		// Normalize and create result
		return normalizeAndCreate(resultSign, resultExp, resultMantissa, hadCarryOut);
		
	}

	// Subtracts the other ExtendedFloat from this one and returns a new ExtendedFloat
	public ExtendedFloat subtract(ExtendedFloat other){
		// this - other = this + (-other)

		ExtendedFloat neg = new ExtendedFloat(other);

		// For NORMAL numbers and INFINITY, we can flip the sign
		// For ZERO and NAN, the sign doesn't matter
		if(neg.isNormal() || neg.isInfinite()){
			neg.sign ^= 1;
		}

		// We use add() for simplicity, remaining special cases are handled there
		return this.add(neg);
	}


	public ExtendedFloat multiply(ExtendedFloat other){
		// NaN handling
		if(this.isNaN() || other.isNaN()) return NaN_CONST;

		// Infinity handling
		if(this.isInfinite() || other.isInfinite()){

			if((this.isZero() && other.isInfinite()) || (this.isInfinite() && other.isZero())){
				return NaN_CONST;
			}

			int resultSign = this.sign ^ other.sign;
			return (resultSign == 1) ? NEGATIVE_INFINITY : POSITIVE_INFINITY;
		}

		// Zero handling
		if(this.isZero() || other.isZero()) return ZERO_CONST;

		// Both are normal, finite numbers
		// Result sign = XOR of signs
		int resultSign = this.sign ^ other.sign;

		// Result exponent = sum of exponents
		// Since both mantissas are normalized (represent values in [1, 2)),
		// their product is in [1, 4), so we may need to adjust by at most 1
		int resultExp = this.exponent + other.exponent;

		// Perform full 1024-bit x 1024-bit multiplication
		// Result is up to 2048 bits, we keep the top 1024 bits
		long[] product = multiplyMantissas(this.mantissa, other.mantissa);

		// Normalize and create the result
		return normalizeAndCreate(resultSign, resultExp, product, false);
	}

	public ExtendedFloat divide(ExtendedFloat other){
		// NaN handling
		if(this.isNaN() || other.isNaN()) return NaN_CONST;

		// Infinity handling
		if(this.isInfinite() || other.isInfinite()){
			// infinity / infinity -> NaN
			if(this.isInfinite() && other.isInfinite()) return NaN_CONST;


			// Numerator is infinite, denominator is finite or zero
			if(this.isInfinite()){
				// infinite / 0 -> -/+infinity (treat like division by zero)
				// infinite / finite -> -/+infinity
				int resultSign = this.sign ^ other.sign;
				return (resultSign == 1) ? NEGATIVE_INFINITY : POSITIVE_INFINITY;
			}

			// Numerator is finite, denominator is infinite -> 0
			return ZERO_CONST; 
		}

		// Zero handling (now both are finite)
		if(this.isZero() || other.isZero()){
			if(this.isZero() && other.isZero()) return NaN_CONST;

			if(this.isZero()) return ZERO_CONST; // other is finite nonzero

			// this is finite nonzero and other is zero (division by zero)
			int resultSign = this.sign ^ other.sign;
			return (resultSign == 1) ? NEGATIVE_INFINITY : POSITIVE_INFINITY;
		}

		// Both are normal finite numbers
		int resultSign = this.sign ^ other.sign;

		// Result exponent: difference of exponents
		// Since both mantissas are normalized (represent values in [1, 2)),
		// their quotient is in (0.5, 2), so we may need to adjust by at most 1
		int resultExp = this.exponent - other.exponent;

		DivMantissaResult dr = divideMantissasBI(this.mantissa, other.mantissa);
		long[] quotient = dr.mantissa;
		resultExp += dr.expAdjust;
		return normalizeAndCreate(resultSign, resultExp, quotient, false);

	} 

	// Raises this ExtendedFloat to an integer power.
	public ExtendedFloat pow(int exponent){
		// NaN handling
		if(this.isNaN()) return NaN_CONST;

		// Exponent = 0
		if(exponent == 0){
			if(this.isZero()) return NaN_CONST; // 0^0 -> NaN

			return ONE_CONST; // x^0 = 1
		}

		// Base is Zero
		if(this.isZero()) return (exponent > 0 ? ZERO_CONST : POSITIVE_INFINITY);

		// Base is infinity
		if(this.isInfinite()){
			if(exponent > 0){
				if(!this.isNegative()) return POSITIVE_INFINITY;

				// Base is -infinity
				if(exponent%2 == 0){ 
					return POSITIVE_INFINITY; // even exponent -> +infinity
				}
				return NEGATIVE_INFINITY; // odd exponent -> -infinity
			}

			return ZERO_CONST; // exponent < 0
		}

		// At this point: this is finite and nonzero, exponent is nonzero

		// Exponent = 1
		if(exponent == 1) return new ExtendedFloat(this);

		// Exponent = -1
		if(exponent == -1) return ONE_CONST.divide(this); // 1 / this


		// Perform binary exponentiation
		int n = exponent;
		boolean negativePower = (n < 0);
		n = negativePower ? -n : n; // must invert sign at end if negative here

		ExtendedFloat result = ONE_CONST;
		ExtendedFloat base = new ExtendedFloat(this); // copy
		while(n > 0){
			if((n & 1) == 1){
				result = result.multiply(base);
			}
			base = base.multiply(base);
			n >>= 1;
		}

		if(negativePower){
			result = ONE_CONST.divide(result);
		}

		return result;
	}

	public ExtendedFloat sqrt(){
		// Special cases
		if(this.isNaN()) return NaN_CONST;
		if(this.isNegative() && !this.isZero()) return NaN_CONST; // sqrt of negative
		if(this.isZero()) return ZERO_CONST;
		if(this.isInfinite()) return POSITIVE_INFINITY;

		// For normal positive numbers, use Newton-Raphson
		// x_{n+1} = (x_n + a/x_n) / 2

		// Start with a reasonable initial guess based on exponent
		// If a = M * 2^exp where M is in [1, 2), then sqrt(a) ~= sqrt(M) + 2^(exp/2)
		// We can estimate sqrt(M) ~= 1.4 (geometric mean of 1 and 2)

		ExtendedFloat guess;
		int halfExp = this.exponent / 2;

		// Create initial guess 1.4 * 2^(exp/2)
		// We'll use 1.5 for simplicity
		guess = new ExtendedFloat(1.5);
		guess.exponent = halfExp;

		// We need about 10-15 iterations for full 1024-bit precision
		ExtendedFloat two = new ExtendedFloat(2.0);
		ExtendedFloat prev;

		for(int i = 0; i < 20; i++){
			// x_{n+1} = (x_n + a/x_n) / 2
			ExtendedFloat quotient = this.divide(guess);
			ExtendedFloat sum = guess.add(quotient);
			ExtendedFloat next = sum.divide(two);

			// Check for convergence by comparing with previous iteration
			if(i > 0){
				ExtendedFloat diff = next.subtract(guess).abs();
				// If diff is extremely small relative to next, we've converged
				if(diff.isZero() || (diff.exponent < next.exponent - 1020)){
					guess = next;
					break;
				}
			}

			guess = next; 
		}

		return guess;
	}


	/**
	 * Returns a new ExtendedFloat with the sign flipped.
	 * 
	 * NaN stays NaN, zero stays ZERO (no signed zero), and
	 * infinities flip between +INFINITY and -INFINITY
	 */
	public ExtendedFloat negate(){
		if(this.isNaN()) return NaN_CONST;

		// Not using signed zeros currently
		if(this.isZero()) return ZERO_CONST;

		if(this.isInfinite()){
			return (this.isNegative() ? POSITIVE_INFINITY : NEGATIVE_INFINITY);
		}

		// Normal
		int flippedSign = this.sign ^ 1;
		return new ExtendedFloat(flippedSign, this.exponent, this.mantissa);
	}

	/**
	 * Returns the absolute value as a new ExtendedFloat
	 * 
	 * NaN stays NaN
	 */
	public ExtendedFloat abs(){
		if(this.isNaN()) return NaN_CONST;

		if(this.isZero()) return ZERO_CONST;

		if(this.isInfinite()) return POSITIVE_INFINITY;

		// Normal finite value: clear sign using private copy constructor
		return new ExtendedFloat(0, this.exponent, this.mantissa);
	}

	
	// Compares extendedNum with (shifted << shiftAmount)
	private static int compareWithShifted(long[] extendedNum, long[] shifted, int shiftAmount){
		// shiftAmount is in range [1024, 2047]
		// We need to compare extendedNum with (shifted << shiftAmount)

		int limbShift = shiftAmount / 64;
		int bitShift = shiftAmount % 64;

		// Compare from MSB to LSB
		for(int i = 31; i >= 0; i--){
			long extVal = extendedNum[i];
			long shiftedVal = getShiftedLimb(shifted, i, limbShift, bitShift);

			if(extVal == shiftedVal) continue;
			return Long.compareUnsigned(extVal, shiftedVal);
		}
		return 0; // Equal
	}

	// Gets the value of (shifted << shiftAmount) at limb position limbPos
	private static long getShiftedLimb(long[] shifted, int limbPos, int limbShift, int bitShift){
		// (shifted << shiftAmount) has shifted[j] contributing to output limbs
		// starting at position limbShift + j

		/* For output limb at position limbPos, we need:
			- bits from shifted[limbPos - limbShift] shifted left by bitShift
			- bits from shifted[limbPos - limbShift - 1] shifted right by (64 - bitShift)
		*/

		int srcIndex = limbPos - limbShift;

		if(srcIndex < 0 || srcIndex >= NUM_LIMBS){
			return 0L; // Out of range
		}

		long val = shifted[srcIndex] << bitShift;

		if(bitShift != 0 && srcIndex > 0){
			val |= shifted[srcIndex - 1] >>> (64 - bitShift);
		}

		return val;
	}

	// Subtracts (shifted << shiftAmount) from extendedNum in place
	private static void subtractShifted(long[] extendedNum, long[] shifted, int shiftAmount){
		int limbShift = shiftAmount / 64;
		int bitShift = shiftAmount % 64;

		long borrow = 0L;

		for(int i = 0; i < 32; i++){
			long extVal = extendedNum[i];
			long shiftedVal = getShiftedLimb(shifted, i, limbShift, bitShift);

			long diff = extVal - shiftedVal - borrow;

			// Detect borrow
			long subtrahend = shiftedVal + borrow;
			if(Long.compareUnsigned(subtrahend, shiftedVal) < 0 || 
				Long.compareUnsigned(extVal, subtrahend) < 0){
				borrow = 1L;
			} else{
				borrow = 0L;
			}

			extendedNum[i] = diff;
		}
	}

	// Multiplies two 1024-bit mantissas and returns the most significant 1024 bits
	private static long[] multiplyMantissas(long[] a, long[] b){
		// full product storage: 32 limbs (2048 bits)
		long[] fullProduct = new long[NUM_LIMBS * 2];

		// a[i] * b[j] contributes to fullProduct[i+j] and fullProduct[i+j+1]
		for(int i = 0; i < NUM_LIMBS; i++){
			long aLimb = a[i];
			if(aLimb == 0) continue;

			long carry = 0;
			for(int j = 0; j < NUM_LIMBS; j++){
				long bLimb = b[j];

				// Multiply aLimb x bLimb (produces 128-bit result)
				// We split this into high and low 64-bit parts
				long productLow = multiplyLow(aLimb, bLimb);
				long productHigh = multiplyHigh(aLimb, bLimb);

				// Add to existing value at position i+j
				int pos = i + j;
				long sum = fullProduct[pos] + productLow + carry;

				// Detect carry from addition
				long newCarry = 0;
				if(Long.compareUnsigned(sum, fullProduct[pos]) < 0 ||
					(carry != 0 && Long.compareUnsigned(sum, fullProduct[pos]) == 0)){
					newCarry = 1;
				}

				fullProduct[pos] = sum;
				carry = productHigh + newCarry;
			}

			// Propagate remaining carry
			int pos = i + NUM_LIMBS;
			while(carry != 0 && pos < fullProduct.length){
				long sum = fullProduct[pos] + carry;
				if(Long.compareUnsigned(sum, fullProduct[pos]) < 0){
					fullProduct[pos] = sum;
					carry = 1;
				} else{
					fullProduct[pos] = sum;
					carry = 0;
				}
				pos++;
			}
		}

		// We want (a*b) >> 1023
		// 1023 = 15*64 + 63, so each output limb pulls:
		// - the top 1 bit of fullProduct[i+15] (>>> 63)
		// - plus the remaining 63 bits from fullProduct[i+16] shifted left by 1
		long[] result = new long[NUM_LIMBS];
		for (int i = 0; i < NUM_LIMBS; i++) {
		    long lowPart  = fullProduct[i + (NUM_LIMBS - 1)] >>> 63; // 1 bit
    		long highPart = fullProduct[i + NUM_LIMBS] << 1;         // 63 bits (plus 0 fill)
    		result[i] = lowPart | highPart;
		}

		return result;

	}

	// Computes the low 64 bits of a x b (unsigned)
	private static long multiplyLow(long a, long b){
		return a * b;
	}

	// Computes the high 64 bits of a x b (unsigned)
	private static long multiplyHigh(long a, long b){
		// Split each 64-bit value into high and low 32-bit parts
		long aLow = a & 0xFFFFFFFFL;
		long aHigh = a >>> 32;
		long bLow = b & 0xFFFFFFFFL;
		long bHigh = b >>> 32;

		// Compute partial products
		long p0 = aLow * bLow;
		long p1 = aLow * bHigh;
		long p2 = aHigh * bLow;
		long p3 = aHigh * bHigh;

		// Combine partial products
		// p0 contributes to bits 0-63
		// p1 and p2 contribute to bits 32-95
		// p3 contributes to bits 64-127

		long middle = p1 + (p0 >>> 32) + (p2 & 0xFFFFFFFFL);
		long high = p3 + (p2 >>> 32) + (middle >>> 32);

		return high;
	}


	// Shift mantissa right by 'bits' (unsigned big integer divide by 2^bits)
	private static long[] shiftRightMagnitude(long[] src, int bits){
		if(bits <= 0){
			return src.clone();
		}
		if(bits >= 64 * NUM_LIMBS){
			return new long[NUM_LIMBS];
		}

		int limbShift = bits / 64;
		int bitShift = bits % 64;

		long[] res = new long[NUM_LIMBS];

		for(int i = 0; i < NUM_LIMBS; i++){
			int srcIndex = i + limbShift;
			if(srcIndex >= NUM_LIMBS){
				break;
			}
			long val = (src[srcIndex] >>> bitShift);
			if(bitShift != 0 && srcIndex + 1 < NUM_LIMBS){
				val |= (src[srcIndex+1] << (64 - bitShift));
			}
			res[i] = val;
		}
		return res;
	}

	// Shifts mantissa left by 'bits' (unsigned big integer multiply by 2^bits)
	private static long[] shiftLeftMagnitude(long[] src, int bits){
		if(bits <= 0){
			return src.clone();
		}
		if(bits >= 64 * NUM_LIMBS){
			// Everything shifts out of our 1024-bit window
			return new long[NUM_LIMBS];
		}

		int limbShift = bits / 64;
		int bitShift = bits % 64;

		long[] res = new long[NUM_LIMBS];

		for(int i = NUM_LIMBS - 1; i>=0; i--){
			int srcIndex = i - limbShift;
			if(srcIndex < 0){
				continue;
			}
			long val = (src[srcIndex] << bitShift);
			if(bitShift != 0 && srcIndex - 1 >= 0){
				val |= (src[srcIndex - 1] >>> (64- bitShift));
			}
			res[i] = val;
		}
		return res;
	}

	//Compare magnitudes of two mantissas as unsigned big integers
	// returns >0 if a > b, 0 if equal, <0 if a < b
	private static int compareMagnitude(long[] a, long[] b){
		for(int i = NUM_LIMBS - 1; i >= 0; i--){
			long x = a[i];
			long y = b[i];
			if(x == y){
				continue;
			}
			// compare as unsigned
			return Long.compareUnsigned(x, y);
		}
		return 0;
	}

	// Helper class to return both mantissa and carry from addition
	private static class AddResult{
		long[] mantissa;
		boolean carryOut;

		AddResult(long[] mantissa, boolean carryOut){
			this.mantissa = mantissa;
			this.carryOut = carryOut;
		}
	}

	// Add two mantissas as unsigned big integers (ignore overflow beyond 1024 bits)
	private static AddResult addMagnitudeWithCarry(long[] a, long[] b){
		long[] res = new long[NUM_LIMBS];
		long carry = 0L;
		for(int i = 0; i < NUM_LIMBS; i++){
			long av = a[i];
			long bv = b[i];

			// Add with carry
			long sum = av + bv + carry;

			// Detect carry: overflow occurs if sum < av (when no carry)
			// or if sum <= av (when carry was 1)
			if(carry == 0){
				carry = Long.compareUnsigned(sum, av) < 0 ? 1L : 0L;
			} else{
				carry = Long.compareUnsigned(sum, av) <= 0 ? 1L : 0L;
			}

			res[i] = sum;
		}
		// Return final carry beyond bit 1023
		return new AddResult(res, carry != 0);
	}

	// Subtract b from a as unsigned big integer. assumes a >= b
	private static long[] subtractMagnitude(long[] a, long[] b){
		long[] res = new long[NUM_LIMBS];
		long borrow = 0L;
		for(int i = 0; i < NUM_LIMBS; i++){
			long av = a[i];
			long bv = b[i];

			// Subtract with borrow
			long diff = av - bv - borrow;

			// Detect borrow, we need to borrow if av < (bv + borrow)
			long subtrahend = bv + borrow;
			// Check if bv + borrow overflows, or if av < subtrahend
			if(Long.compareUnsigned(subtrahend, bv) < 0 || Long.compareUnsigned(av, subtrahend) < 0){
				borrow = 1L;
			} else{
				borrow = 0L;
			}

			res[i] = diff;
		}
		return res;
	}

	// Check if mantissa is all zeros
	private static boolean isMantissaZero(long[] m){
		for(long limb : m){
			if(limb != 0L){
				return false;
			}
		}
		return true;
	}

	private static BigInteger mantissaToBigInteger(long[] m) {
    	// mantissa[0] is least-significant limb, mantissa[15] is most-significant limb
	    byte[] bytes = new byte[NUM_LIMBS * 8]; // 128 bytes
	    int p = 0;
	    for (int i = NUM_LIMBS - 1; i >= 0; i--) {
	        long v = m[i];
	        bytes[p++] = (byte) (v >>> 56);
	        bytes[p++] = (byte) (v >>> 48);
	        bytes[p++] = (byte) (v >>> 40);
	        bytes[p++] = (byte) (v >>> 32);
	        bytes[p++] = (byte) (v >>> 24);
	        bytes[p++] = (byte) (v >>> 16);
	        bytes[p++] = (byte) (v >>> 8);
	        bytes[p++] = (byte) (v);
	    }
	    return new BigInteger(1, bytes);
	}

	private static long[] bigIntegerToMantissa(BigInteger x) {
	    long[] out = new long[NUM_LIMBS];
	    byte[] bytes = x.toByteArray(); // big-endian, may be shorter than 128 bytes

	    // Copy into a fixed 128-byte buffer, right-aligned
	    byte[] buf = new byte[NUM_LIMBS * 8];
	    int srcStart = Math.max(0, bytes.length - buf.length);
	    int srcLen = bytes.length - srcStart;
	    System.arraycopy(bytes, srcStart, buf, buf.length - srcLen, srcLen);

	    int p = 0;
	    for (int i = NUM_LIMBS - 1; i >= 0; i--) {
	        long v = 0;
	        v |= ((long) buf[p++] & 0xFF) << 56;
	        v |= ((long) buf[p++] & 0xFF) << 48;
	        v |= ((long) buf[p++] & 0xFF) << 40;
	        v |= ((long) buf[p++] & 0xFF) << 32;
	        v |= ((long) buf[p++] & 0xFF) << 24;
	        v |= ((long) buf[p++] & 0xFF) << 16;
	        v |= ((long) buf[p++] & 0xFF) << 8;
	        v |= ((long) buf[p++] & 0xFF);
	        out[i] = v;
	    }
	    return out;
	}

	private static final class DivMantissaResult {
	    final long[] mantissa;
	    final int expAdjust; // +1 if quotient was 1025 bits and we shifted right by 1
	    DivMantissaResult(long[] mantissa, int expAdjust) {
	        this.mantissa = mantissa;
	        this.expAdjust = expAdjust;
	    }
	}

	private static DivMantissaResult divideMantissasBI(long[] numerator, long[] denominator) {
	    BigInteger num = mantissaToBigInteger(numerator);
	    BigInteger den = mantissaToBigInteger(denominator);

	    // Q = floor((num << 1024) / den)
	    BigInteger q = num.shiftLeft(1023).divide(den);

	    int adjust = 0;
	    // If q is 1025 bits, shift right 1 and adjust exponent by +1
	    if (q.bitLength() > 1024) {
	        q = q.shiftRight(1);
	        adjust = 1;
	    }

	    // Ensure q fits into 1024 bits
	    if (q.bitLength() > 1024) {
	        q = q.and(BigInteger.ONE.shiftLeft(1024).subtract(BigInteger.ONE));
	    }

    	return new DivMantissaResult(bigIntegerToMantissa(q), adjust);
	}


	// Normalize mantissa so highest set bit is at position 1023,
	// adjust exponent accordingly, and create an ExtendedFloat
	private static ExtendedFloat normalizeAndCreate(int sign, int exponent, long[] mantissa, boolean hadCarryOut){
		if(isMantissaZero(mantissa) && !hadCarryOut){
			ExtendedFloat z = new ExtendedFloat();
			z.sign = 0;
			return z;
		}

		long[] norm = mantissa.clone();
		int newExp = exponent;

		long topLimb = mantissa[NUM_LIMBS - 1];

		// If we had a carry out from addition, it means the result overflowed into bit 1024
		// We need to shift right by 1 and increment the exponent
		if(hadCarryOut){
			norm = shiftRightMagnitude(norm, 1);
			// Set the top bit (1023) to 1 to represent the carry
			norm[NUM_LIMBS - 1] |= (1L << 63);
			newExp += 1;

			// Check for exponent overflow immediately
			if(newExp > MAX_EXP){
				return (sign == 1) ? NEGATIVE_INFINITY : POSITIVE_INFINITY;
			}

			// After handling carry, mantissa is already normalized
			return new ExtendedFloat(sign, newExp, norm);
		}

		// Find most significant limb with a set bit
		int msLimb = -1;
		for(int i = NUM_LIMBS - 1; i >= 0; i--){
			if(mantissa[i] != 0L){
				msLimb = i;
				break;
			}
		}

		// If no bits set, return zero
		if(msLimb == -1){
			ExtendedFloat z = new ExtendedFloat();
			z.sign = 0;
			return z;
		}

		long limb = mantissa[msLimb];
		int leadingZeros = Long.numberOfLeadingZeros(limb);
		int msBitInLimb = 63 - leadingZeros;
		int globalBitIndex = msLimb * 64 + msBitInLimb;

		int shift = 1023 - globalBitIndex;

		if(shift > 0){
			// MSB is below bit 1023, shift left to normalize
			norm = shiftLeftMagnitude(norm, shift);
			newExp -= shift;
		} else if(shift < 0){
			// MSB is above 1023 (should not happen after carry-out handling, but be safe)
			norm = shiftRightMagnitude(norm, -shift);
			newExp += (-shift);
		}

		// Apply exponent bounds
		if(newExp > MAX_EXP){
			return (sign == 1) ? NEGATIVE_INFINITY : POSITIVE_INFINITY;
		}
		if(newExp < MIN_EXP){
			ExtendedFloat z = new ExtendedFloat();
			z.sign = sign;
			return z;
		}

		// Create a NORMAL result using internal constructor
		return new ExtendedFloat(sign, newExp, norm);
	}

	// Accessor method which returns the binary (int) sign
	public int getSign(){
		return sign;
	}

	// Accesor method which returns the exponent
	public int getExponent(){
		return exponent;
	}

	// Accesor method which returns the mantissa
	public long[] getMantissa(){
		return mantissa.clone();
	}

	// Returns the enum, Kind, that our ExtendedFloat is representing, as a String
	public String getKind(){
		return this.kind.toString();
	}

	// Converts this ExtendedFloat to a double
	public double toDouble() {
    // Handle non-normal kinds first
    if (this.kind == Kind.NAN) return Double.NaN;
    if (this.kind == Kind.INFINITY) return (this.sign == 1) ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
    if (this.kind == Kind.ZERO) return 0.0;

    long topLimb = this.mantissa[NUM_LIMBS - 1];
    if (topLimb == 0L) return 0.0;

    // IMPORTANT: never mutate object state in toDouble()
    int exp = this.exponent;

    /*
      Internal layout:
        M is a 1024-bit integer with its top bit at position 1023.
        top limb (mantissa[15]) holds bits 960..1023.

      The top 53 bits we want are bits 1023..971, which are exactly bits 63..11 of topLimb.
      So:
        top53  = bits 63..11  (53 bits)
        roundBit = bit 10
        sticky  = any bit below bit 10 (bits 0..9) OR any lower limb bits
    */

    long top53 = topLimb >>> 11;            // 53-bit significand (includes leading 1 at bit 52)
    long roundBit = (topLimb >>> 10) & 1L;  // next bit after the kept 53 bits

    boolean sticky = (topLimb & 0x3FFL) != 0L; // bits 0..9 in top limb
    if (!sticky) {
        for (int i = NUM_LIMBS - 2; i >= 0; i--) {
            if (this.mantissa[i] != 0L) { sticky = true; break; }
        }
    }

    // Round to nearest, ties to even
    if (roundBit == 1L && (sticky || ((top53 & 1L) == 1L))) {
        top53++;

        // If rounding overflowed from 53 bits (e.g., 1.111.. -> 10.000..)
        if (top53 == (1L << 53)) {
            top53 = 1L << 52; // becomes exactly 1.0 in significand form
            exp++;            // increment exponent (local variable only)
        }
    }

    // Build IEEE-754 double
    long fraction = top53 & 0xFFFFFFFFFFFFFL; // low 52 bits (drop implicit leading 1)
    int doubleExp = exp + 1023;               // bias exponent

    if (doubleExp >= 2047) {
        return (this.sign == 1) ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
    }
    if (doubleExp <= 0) {
        // Underflow: for simplicity return zero (you can add subnormal support later if you want)
        return 0.0;
    }

    long signBit = ((long) this.sign) << 63;
    long expBits  = ((long) doubleExp) << 52;
    long bits = signBit | expBits | fraction;

    return Double.longBitsToDouble(bits);
}


	/**
	 * Returns a simple string representation of this ExtendedFloat.
	 * 
	 * The output depends on the value's kind:
	 * - "ZERO" for zero
	 * - "NaN" for not-a-number
	 * - "+INFINITY" or "-INFINITY" for infinities
	 * - For normal numbers: "NORMAL(sign, exp=E, top=0xXXXXXXXXXXXXXXXX, approx=A)"
	 * 
	 * This representation is intended for debugging and inspecting the
	 * internal state of the ExtendedFloat
	 * 
	 * @return a human-readable description of this ExtendedFloat
	 */
	@Override
	public String toString(){
		if(this.kind == Kind.ZERO) return "ZERO";

		if(this.kind == Kind.NAN) return "NaN";

		if(this.kind == Kind.INFINITY){
			return (this.sign == 1) ? "-INFINITY" : "+INFINITY";
		}

		// Number is normal
		// Output the sign, exponent, partial mantissa (in hex), and an approximation
		// This is formatted as:
		// 		TYPE(sign, exp, top, approx)
		char s = (this.sign == 1 ? '-' : '+');
		long top = this.mantissa[NUM_LIMBS - 1];
		Double approx = this.toDouble();

		// Using String.format() to cleanly format the String representation
		// Convert the top of the mantissa to hex
		// Also use %g which will automatically format our approximation into either decimal 
		// or scientific notation based on which is shorter
		return String.format("NORMAL(%c, exp=%d, top=0x%016X, approx=%g)",s,this.exponent,top,approx);
	}

	// Checks if the ExtendedFloat is zero
	public boolean isZero(){
		return this.kind == Kind.ZERO;
	}

	// Checks if the ExtendedFloat is representing NaN
	public boolean isNaN(){
		return this.kind == Kind.NAN;
	}

	// Checks if the ExtendedFloat is representing Infinity
	public boolean isInfinite(){
		return this.kind == Kind.INFINITY;
	}

	// Checks if the Extended Float is Normal (not zero, NaN, or infinity)
	public boolean isNormal(){
		return this.kind == Kind.NORMAL;
	}

	// Checks if the ExtendedFloat is negative
	public boolean isNegative(){
		return sign == 1;
	}

	/**
	 * Compares the current ExtendedFloat to the ExtendedFloat other
	 * 
	 * -1 if this < other
	 * 0 if this == other
	 * 1 if this > other
	 * 
	 * @param comparisonFloat ExtendedFloat object to be compared
	 * 
	 * @return -1/0/1 based on same ordering as standard compareTo methods
	 */
	public int compareTo(ExtendedFloat other){
		// NaN handling
		if(this.isNaN() || other.isNaN()){
			// We mimic Double.compare() logic for handling NaN, since NaN is "unordered" mathematically

			// All NaNs are considered greather than any non-NaN
			if(this.isNaN() && !other.isNaN()) return 1; // NaN > non-NaN
			if(other.isNaN() && !this.isNaN()) return -1; // non-NaN < NaN
			return 0; // both NaN
		}

		// Infinity handling
		if(this.isInfinite() && other.isInfinite()){
			if(this.sign == other.sign) return 0;

			// Different sign infinities
			return (this.isNegative() ? -1 : 1);
		}

		if(this.isInfinite() && !other.isInfinite()) return (this.isNegative() ? -1 : 1);

		if(!this.isInfinite() && other.isInfinite()) return (other.isNegative() ? 1 : -1);

		// Zero handling
		if(this.isZero() || other.isZero()){
			// Both zero
			if(this.isZero() && other.isZero()) return 0;

			if(this.isZero()) return (other.isNegative() ? 1 : -1);

			// Other is zero
			return this.isNegative() ? -1 : 1;
		}

		// Both are finite and nonzero
		if(this.isNegative() && !other.isNegative()) return -1;
		if(!this.isNegative() && other.isNegative()) return 1;

		// Both are finite and nonzero, and have the same sign
		int cmp;
		//Compare exponents first
		if(this.exponent != other.exponent){
			if(this.exponent > other.exponent){
				cmp = 1;
			} else{
				cmp = -1;
			}
		} else{
			// Compare mantissas using helper method
			cmp = compareMagnitude(this.mantissa, other.mantissa);
		}

		if(this.isNegative()){
			// Flip logic if both are negative
			return -cmp;
		}

		// Both are positive
		return cmp;
	}

}