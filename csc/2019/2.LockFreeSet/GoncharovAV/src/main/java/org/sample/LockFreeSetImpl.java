package org.sample;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicMarkableReference;

public class LockFreeSetImpl<T extends Comparable<T>> implements LockFreeSet<T> {

    static final int maxHeight = 32;

    private final SkipListNode<T> head;
    private final SkipListNode<T> tail;

    public LockFreeSetImpl() {
        head = new SkipListNode<>(null, maxHeight);
        tail = new SkipListNode<>(null, maxHeight);

        for (int i = 0; i < maxHeight; ++i) {
            head.next.get(i).compareAndSet(null, tail, false, false);
        }
    }

    @Override
    public boolean add(T value) {
        retry:
        while (true) {
            Bounds<T> search_result = search(value);
            SkipListNode<T> closest_node = search_result.rights.get(0);

            if (closest_node != tail && closest_node.value.compareTo(value) == 0) {
                int height = closest_node.next.size();
                if (closest_node.next.get(0).isMarked()) {
                    continue retry;
                } else
                    return false;
            }

            SkipListNode<T> new_node = SkipListNode.make(value);
            for (int i = 0; i < new_node.next.size(); i++)
                new_node.next.get(i).compareAndSet(null, search_result.rights.get(i), false, false);

            if (!search_result.lefts.get(0).next.get(0).compareAndSet(search_result.rights.get(0), new_node, false, false))
                continue retry;

            for (int i = 1; i < new_node.next.size(); ++i) {
                while (true) {
                    if (new_node.next.get(i).isMarked())
                        break;

                    if (search_result.lefts.get(i).next.get(i).compareAndSet(search_result.rights.get(i), new_node, false, false))
                        break;

                    search_result = search(value);
                }
            }

            search(value);
            return true;
        }
    }

    @Override
    public boolean remove(T value) {
        Bounds<T> search_result = search(value);
        SkipListNode<T> closest_node = search_result.rights.get(0);

        if (closest_node == tail || closest_node.value.compareTo(value) != 0)
            return false;

        boolean marked_by_current_thread = mark(closest_node);
        search(value);

        return marked_by_current_thread;
    }

    @Override
    public boolean contains(T value) {
        Bounds<T> search_result = search(value);

        SkipListNode<T> closest_node = search_result.rights.get(0);
        if (closest_node != tail && closest_node.value.compareTo(value) == 0)
            return true;
        else
            return false;
    }

    @Override
    public boolean isEmpty() {

        SkipListNode<T> second = head.next.get(0).getReference();
        while (second != tail && second.next.get(0).isMarked())
            second = second.next.get(0).getReference();

        return second == tail;
    }

    @Override
    public Iterator<T> iterator() {
        return null;
    }

    private Bounds<T> search(T value) {
        retry:
        while (true) {
            SkipListNode<T> left = head;

            ArrayList<SkipListNode<T>> lefts = new ArrayList<>();
            ArrayList<SkipListNode<T>> rights = new ArrayList<>();

            for (int i = maxHeight - 1; i >= 0; i--) {
                if (left.next.get(i).isMarked())
                    continue retry;

                SkipListNode<T> left_next = left.next.get(i).getReference();
                SkipListNode<T> right = left_next;
                SkipListNode<T> right_next = right.next.get(i).getReference();
                while (right.next.get(i).isMarked()) {
                    right = right_next;
                    right_next = right.next.get(i).getReference();
                }

                while (right != tail && right.value.compareTo(value) < 0) {

                    left = right;
                    left_next = right_next;
                    right = right_next;
                    right_next = right.next.get(i).getReference();
                    while (right.next.get(i).isMarked()) {
                        right = right_next;
                        right_next = right.next.get(i).getReference();
                    }
                }

                if (left_next != right) {
                    boolean remove_marked_success = left.next.get(i).compareAndSet(left_next, right, false, false);
                    if (!remove_marked_success)
                        continue retry;
                }

                lefts.add(left);
                rights.add(right);

            }

            Collections.reverse(lefts);
            Collections.reverse(rights);

            return new Bounds<>(lefts, rights);
        }
    }

    private boolean mark(SkipListNode<T> node) {
        boolean marked_by_current_thread = true;
        for (int i = node.next.size() - 1; i >= 0; i--) {
            boolean mark_success;
            do {
                if (node.next.get(i).isMarked()) {
                    marked_by_current_thread = false;
                    break;
                } else
                    marked_by_current_thread = true;

                SkipListNode<T> node_next = node.next.get(i).getReference();
                mark_success = node.next.get(i).compareAndSet(node_next, node_next, false, true);
            } while (!mark_success);
        }
        return marked_by_current_thread;
    }
}


class SkipListNode<T extends Comparable<T>> {

    T value;
    ArrayList<AtomicMarkableReference<SkipListNode<T>>> next;

    static <T2 extends Comparable<T2>> SkipListNode<T2> make(T2 value) {
        long number =  1 + (long) (Math.random() * (1L << (LockFreeSetImpl.maxHeight-1)));
        int height = leastSignificantBitPosition(number);

        return new SkipListNode<>(value, height);
    }

    static int leastSignificantBitPosition(long number) {
        number &= -number;

        int pos = 0;
        while (number != 0) {
            number >>= 1;
            pos++;
        }

        return pos;
    }

    SkipListNode(T value, int height) {
        this.value = value;

        next = new ArrayList<>(height);
        for (int i = 0; i < height; i++) {
            next.add(new AtomicMarkableReference<>(null, false));
        }
    }
}


class Bounds<T extends Comparable<T>> {
    final ArrayList<SkipListNode<T>> lefts;
    final ArrayList<SkipListNode<T>> rights;

    Bounds(ArrayList<SkipListNode<T>> lefts, ArrayList<SkipListNode<T>> rights) {
        this.lefts = lefts;
        this.rights = rights;
    }
}