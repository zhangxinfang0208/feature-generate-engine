package com.example.featuredag.runtime;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** FrozenList 是引擎识别「无需再防御复制」的唯一标记：两种构造都不可修改，语义与普通 List 一致。 */
public class FrozenListTest {

    @Test
    public void wrapKeepsTheBackingListAndReturnsItselfWhenAlreadyFrozen() {
        List<String> backing = new ArrayList<>(Arrays.asList("a", "b"));
        FrozenList<String> frozen = FrozenList.wrap(backing);

        backing.set(1, "c");
        assertEquals(List.of("a", "c"), frozen);
        assertSame(frozen, FrozenList.wrap(frozen));
        assertEquals(frozen, FrozenList.wrap(frozen));
    }

    @Test
    public void copyOfDoesNotAliasTheOriginalList() {
        List<String> original = new ArrayList<>(Arrays.asList("a", "b"));
        List<String> frozen = FrozenList.copyOf(original);

        original.set(1, "c");
        assertEquals(List.of("a", "b"), frozen);
        assertNotSame(original, frozen);
    }

    @Test
    public void sequentialInputsAreCopiedSoIndexAccessStaysConstantTime() {
        List<Integer> linked = new LinkedList<>(Arrays.asList(1, 2, 3));
        FrozenList<Integer> frozen = FrozenList.wrap(linked);

        assertTrue(frozen instanceof java.util.RandomAccess);
        linked.add(4);
        assertEquals(List.of(1, 2, 3), frozen);
    }

    @Test
    public void isUnmodifiable() {
        List<String> frozen = FrozenList.wrap(new ArrayList<>(List.of("a")));
        List<String> copy = FrozenList.copyOf(List.of("a"));

        assertThrows(UnsupportedOperationException.class, () -> frozen.set(0, "b"));
        assertThrows(UnsupportedOperationException.class, () -> frozen.add("b"));
        assertThrows(UnsupportedOperationException.class, () -> frozen.remove(0));
        assertThrows(UnsupportedOperationException.class, () -> frozen.clear());
        assertThrows(UnsupportedOperationException.class, () -> {
            java.util.Iterator<String> iterator = frozen.iterator();
            iterator.next();
            iterator.remove();
        });
        assertThrows(UnsupportedOperationException.class, () -> frozen.sort(String::compareTo));
        assertThrows(UnsupportedOperationException.class, () -> copy.add("b"));
        assertThrows(IndexOutOfBoundsException.class, () -> frozen.get(1));
    }

    @Test
    public void listContractMatchesArrayListIncludingNullElements() {
        List<String> reference = Arrays.asList("a", null, "c");
        List<String> frozen = FrozenList.wrap(reference);

        assertEquals(reference, frozen);
        assertEquals(reference.hashCode(), frozen.hashCode());
        assertEquals(reference.toString(), frozen.toString());
        assertEquals("c", frozen.get(2));
        assertEquals(1, frozen.indexOf(null));
    }
}
