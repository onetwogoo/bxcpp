package org.anarres.cpp;

import javax.annotation.Nonnull;
import java.util.*;

public final class FList<E> extends AbstractList<E> {
    private static final FList EMPTY = new FList();

    @Nonnull
    private static <E> ArrayList<E> asArrayList(final List<E> list) {
        if (list instanceof ArrayList) return (ArrayList<E>)list;
        ArrayList<E> result = new ArrayList<>(list.size());
        for (E e: list) {
            result.add(e);
        }
        return result;
    }

    @Nonnull
    @SuppressWarnings("unchecked")
    public static <E> FList<E> empty() {
        return EMPTY;
    }

    @Nonnull
    public static <E> FList<E> singleton(E e) {
        return new FList<>(e, empty());
    }

    @Nonnull
    public static <E> FList<E> fromReversed(final List<E> list) {
        FList<E> result = empty();
        for (E e: list) {
            result = new FList<>(e, result);
        }
        return result;
    }

    @Nonnull
    public static <E> FList<E> from(final List<E> list) {
        if (list instanceof FList) {
            return (FList<E>)list;
        }
        return from(asArrayList(list));
    }

    @Nonnull
    public static <E> FList<E> from(final ArrayList<E> list) {
        FList<E> result = empty();
        for (int i = list.size() - 1; i >= 0; i--) {
            result = new FList<E>(list.get(i), result);
        }
        return result;
    }

    @Nonnull
    public static <E> FList<E> concat(final List<E> list, FList<E> flist) {
        ArrayList<E> arrayList = asArrayList(list);
        for (int i = arrayList.size() - 1; i >= 0; i--) {
            flist = new FList<>(arrayList.get(i), flist);
        }
        return flist;
    }

    @Nonnull
    public final FList<E> next;
    public final E cur;
    public final int size;

    private FList(){
        cur = null;
        next = this;
        size = 0;
    }

    public FList(final E cur, FList<E> next) {
        this.cur = cur;
        this.next = next;
        this.size = next.size + 1;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public E get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException();
        return subList(index).cur;
    }

    @Override
    public java.util.Iterator<E> iterator() {
        return listIterator();
    }

    @Override
    @Nonnull
    public ListIterator<E> listIterator(int index) {
        return new FList.Iterator<>(subList(index), index);
    }

    @Override
    @Nonnull
    public FList<E> subList(int fromIndex, int toIndex) {
        if (fromIndex < 0 || toIndex > size || fromIndex > toIndex)
            throw new IndexOutOfBoundsException();
        if (fromIndex == toIndex) return empty();
        if (toIndex == size) return subList(fromIndex);

        FList<E> list = this;
        for (int i = 0; i < fromIndex; i++) {
            list = list.next;
        }
        ArrayList<E> elements = new ArrayList<E>(toIndex - fromIndex);
        for (int i = fromIndex; i < toIndex; i++) {
            elements.add(list.cur);
            list = list.next;
        }
        return FList.from(elements);
    }

    @Nonnull
    public FList<E> subList(int fromIndex) {
        if (fromIndex < 0 || fromIndex > size) throw new IndexOutOfBoundsException();
        if (fromIndex == size) return empty();

        FList<E> list = this;
        for (int i = 0; i < fromIndex; i++) {
            list = list.next;
        }
        return list;
    }

    @Nonnull
    public FList<E> reversedSubList(int fromIndex, int toIndex) {
        if (fromIndex < 0 || toIndex > size || fromIndex > toIndex)
            throw new IndexOutOfBoundsException();
        if (fromIndex == toIndex) return empty();

        FList<E> list = this;
        for (int i = 0; i < fromIndex; i++) {
            list = list.next;
        }

        FList<E> result = empty();
        for (int i = fromIndex; i < toIndex; i++) {
            result = new FList<>(list.cur, result);
            list = list.next;
        }
        return result;
    }

    public boolean equals(@Nonnull FList<E> other) {
        if (this.size != other.size) return false;
        FList<E> self = this;
        while (self != EMPTY) {
            if (self == other) return true;
            if (self.cur == null) {
                if (other.cur != null) return false;
            } else {
                if (!self.cur.equals(other.cur)) return false;
            }
            self = self.next;
            other = other.next;
        }
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean equals(Object o) {
        if (o instanceof FList) {
            return this.equals((FList<E>)o);
        }
        return super.equals(o);
    }

    static final class Iterator<E> implements ListIterator<E> {

        private FList<E> list;
        private int index;

        Iterator(FList<E> list, int index) {
            this.list = list;
            this.index = index;
        }

        @Override
        public boolean hasNext() {
            return list != EMPTY;
        }

        @Override
        public E next() {
            E element = list.cur;
            index++;
            list = list.next;
            return element;
        }

        @Override
        public boolean hasPrevious() {
            return index > 0;
        }

        @Override
        public E previous() {
            throw new UnsupportedOperationException("not supported");
        }

        @Override
        public int nextIndex() {
            return index;
        }

        @Override
        public int previousIndex() {
            return index - 1;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("immutable list");
        }

        @Override
        public void set(E e) {
            throw new UnsupportedOperationException("immutable list");
        }

        @Override
        public void add(E e) {
            throw new UnsupportedOperationException("immutable list");
        }
    }
}
