# ExtendedFloat (Java)

This project implements a custom floating-point number type called `ExtendedFloat`.

The goal is to better understand how floating-point values are represented internally (sign, exponent, mantissa) and how arithmetic operations behave at the bit level.

The internal representation uses:
- A sign bit
- An integer exponent
- A 1024-bit mantissa stored as 16 long limbs

Basic arithmetic operations are supported, including addition, subtraction, multiplication, division, square root, exponentiation, and comparison.

Multiplication is implemented using manual limb-based arithmetic.
Division uses BigInteger internally for correctness and simplicity.

---

## Features

- Custom floating-point representation
- 1024-bit mantissa
- Handling of special values (NaN, Infinity, Zero)
- Addition and subtraction with exponent alignment
- Limb-based multiplication
- BigInteger-backed division
- Conversion back to double
- Comparison and ordering
- Demonstration program with correctness checks

---

## How to Compile and Run

Compile:

    javac ExtendedFloat.java Demo.java

Run:

    java Demo

The demo program performs:
- Arithmetic correctness checks
- Special value behavior checks
- Floating-point behavior examples
- Internal representation display
- Basic ordering tests

---

## Notes

- This is not intended to replace IEEE-754 double.
- The focus is on understanding representation and arithmetic mechanics.
- Division uses BigInteger to ensure correctness at 1024-bit precision.
- Conversion to double follows standard IEEE rounding behavior.
