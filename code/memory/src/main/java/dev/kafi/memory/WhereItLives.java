package dev.kafi.memory;

/**
 * Where a value actually lives is decided by what kind of variable holds it, not by
 * whether its type happens to be primitive. A primitive local sits in the frame; a
 * primitive field sits inside its object, wherever that object is.
 */
public final class WhereItLives {

    private WhereItLives() {
    }

    // region:order
    static final class Order {
        // Fields, primitive or not, are part of the object. When the object is on the
        // heap, so are these, including the int.
        int quantity;
        String symbol;
    }
    // endregion:order

    // region:frame
    static int place(int quantity) {
        // A primitive local. The value 5 is written into this frame.
        int fee = 5;

        // A reference local. The reference is in the frame; the Order is not.
        Order order = new Order();

        // Both writes reach into the object on the heap, not into this frame.
        order.quantity = quantity;
        order.symbol = "VOD.L";

        // Two locals, one object. Nothing is copied.
        Order alias = order;
        alias.quantity += 1;

        // Not a pointer: no arithmetic is possible on it, and the collector is free to
        // move the object it names, so it is not a stable address.
        Order none = null;

        return order.quantity + fee + (none == null ? 0 : 1) + alias.quantity;
    }
    // endregion:frame

    public static void main(String[] args) {
        System.out.println(place(2));
    }
}
