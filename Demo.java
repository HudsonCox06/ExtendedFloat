import java.util.Arrays;

public class Demo {

    private static void assertBitsEqual(double expected, double actual, String msg) {
        long e = Double.doubleToLongBits(expected);
        long a = Double.doubleToLongBits(actual);
        if (e != a) {
            throw new AssertionError(msg + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertAlmost(double expected, double actual, double eps, String msg) {
        if (Double.isNaN(expected)) {
            if (!Double.isNaN(actual)) throw new AssertionError(msg + " expected=NaN actual=" + actual);
            return;
        }
        if (Double.isInfinite(expected)) {
            assertBitsEqual(expected, actual, msg);
            return;
        }
        double diff = Math.abs(expected - actual);
        if (diff > eps) {
            throw new AssertionError(msg + " expected=" + expected + " actual=" + actual + " diff=" + diff);
        }
    }

    private static void section(String title) {
        System.out.println("\n=== " + title + " ===");
    }

    public static void main(String[] args) {
        System.out.println("ExtendedFloat Demo\n");

        // 1) Show the representation + approximate value
        section("Showcase values");
        ExtendedFloat a = new ExtendedFloat(1.5);
        ExtendedFloat b = new ExtendedFloat(2.25);
        ExtendedFloat c = a.multiply(b);

        System.out.println("a = " + a + "   (approx " + a.toDouble() + ")");
        System.out.println("b = " + b + "  (approx " + b.toDouble() + ")");
        System.out.println("a * b = " + c + "  (approx " + c.toDouble() + ")");

        // 2) Non-mutating toDouble (important property for a numeric type)
        section("toDouble should not mutate state");
        ExtendedFloat one = new ExtendedFloat(1.0);
        double d1 = one.toDouble();
        double d2 = one.toDouble();
        assertBitsEqual(d1, d2, "toDouble mutates value");
        System.out.println("PASS: calling toDouble() twice gives the same result");

        // 3) Basic arithmetic correctness against double
        section("Basic arithmetic checks");
        assertAlmost(1.5 + 2.25, a.add(b).toDouble(), 1e-12, "add");
        assertAlmost(1.5 - 2.25, a.subtract(b).toDouble(), 1e-12, "subtract");
        assertAlmost(1.5 * 2.25, a.multiply(b).toDouble(), 1e-12, "multiply");
        assertAlmost(1.5 / 2.25, a.divide(b).toDouble(), 1e-12, "divide");
        System.out.println("PASS: +, -, *, /");

        // 4) Special values: NaN / Infinity
        section("Special values");
        ExtendedFloat nan = new ExtendedFloat(Double.NaN);
        if (!Double.isNaN(nan.add(a).toDouble())) throw new AssertionError("NaN should propagate");
        System.out.println("PASS: NaN propagation");

        ExtendedFloat posInf = new ExtendedFloat(Double.POSITIVE_INFINITY);
        assertBitsEqual(Double.POSITIVE_INFINITY, posInf.add(a).toDouble(), "+inf + finite");
        System.out.println("PASS: Infinity behavior");

        // 5) One classic floating-point example (not a failure, just a showcase)
        section("Floating-point behavior example (0.1 + 0.2)");
        ExtendedFloat ef01 = new ExtendedFloat(0.1);
        ExtendedFloat ef02 = new ExtendedFloat(0.2);
        ExtendedFloat efSum = ef01.add(ef02);
        System.out.println("ExtendedFloat(0.1) = " + ef01 + "  (approx " + ef01.toDouble() + ")");
        System.out.println("ExtendedFloat(0.2) = " + ef02 + "  (approx " + ef02.toDouble() + ")");
        System.out.println("0.1 + 0.2 = " + efSum + "  (approx " + efSum.toDouble() + ")");
        System.out.println("double 0.1 + 0.2 = " + (0.1 + 0.2));

        // 6) Higher ops (only if your ExtendedFloat implements these)
        section("Other operations");
        ExtendedFloat x = new ExtendedFloat(10.0);
        assertAlmost(Math.sqrt(10.0), x.sqrt().toDouble(), 1e-12, "sqrt(10)");
        System.out.println("PASS: sqrt");

        ExtendedFloat y = new ExtendedFloat(2.5);
        assertAlmost(Math.pow(2.5, -3), y.pow(-3).toDouble(), 1e-12, "pow(2.5,-3)");
        System.out.println("PASS: pow");

        // 7) compareTo sanity
        section("Ordering (compareTo)");
        if (!(new ExtendedFloat(1.0).compareTo(new ExtendedFloat(2.0)) < 0)) {
            throw new AssertionError("compareTo: 1.0 should be < 2.0");
        }
        if (!(new ExtendedFloat(-3.0).compareTo(new ExtendedFloat(-4.0)) > 0)) {
            throw new AssertionError("compareTo: -3.0 should be > -4.0");
        }
        System.out.println("PASS: compareTo");

        System.out.println("\nDemo complete. All checks passed.");
    }
}
