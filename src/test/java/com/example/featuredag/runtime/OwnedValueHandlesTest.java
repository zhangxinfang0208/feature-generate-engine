package com.example.featuredag.runtime;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

/**
 * 运行时独占的列表直接移交值句柄：冻结列表不再防御复制，
 * 公开构造器和其他来源保持拷贝语义，对外仍不可修改。
 */
public class OwnedValueHandlesTest {

    @Test
    public void ownedCandidateVectorSkipsTheCopyAndStaysUnmodifiable() {
        List<Object> owned = new ArrayList<>(Arrays.asList(1, 2));
        CandidateVectorValue vector = CandidateVectorValue.owned(owned);

        // owned 表示调用方交出所有权：句柄直接引用该列表，之后调用方不得再修改它。
        owned.add(3);
        assertEquals(List.of(1, 2, 3), vector.values());
        assertEquals(new CandidateVectorValue(List.of(1, 2, 3)), vector);
        assertThrows(UnsupportedOperationException.class, () -> vector.values().set(0, 9));
        assertThrows(UnsupportedOperationException.class, () -> vector.values().add(4));
    }

    @Test
    public void publicCandidateVectorConstructorStillCopies() {
        List<Object> source = new ArrayList<>(Arrays.asList(1, 2));
        CandidateVectorValue vector = new CandidateVectorValue(source);

        source.set(0, 9);
        assertEquals(List.of(1, 2), vector.values());
        assertThrows(UnsupportedOperationException.class, () -> vector.values().set(0, 9));
    }

    @Test
    public void candidateVectorKeepsRecordEqualitySemantics() {
        CandidateVectorValue first = CandidateVectorValue.owned(new ArrayList<>(Arrays.asList(1, null)));

        assertEquals(first, new CandidateVectorValue(Arrays.asList(1, null)));
        assertEquals(first.hashCode(), new CandidateVectorValue(Arrays.asList(1, null)).hashCode());
        assertNotEquals(first, new CandidateVectorValue(List.of(1)));
        assertEquals("CandidateVectorValue[values=[1, null]]", first.toString());
    }

    @Test
    public void listSequenceValueReusesFrozenListsButCopiesEverythingElse() {
        FrozenList<Integer> frozen = FrozenList.wrap(List.of(1, 2, 3));
        ListSequenceValue fromFrozen = new ListSequenceValue("exec", frozen);
        assertSame(frozen, fromFrozen.values());

        List<Integer> mutable = new ArrayList<>(List.of(1, 2, 3));
        ListSequenceValue fromMutable = new ListSequenceValue("exec", mutable);
        mutable.set(0, 9);
        assertEquals(List.of(1, 2, 3), fromMutable.values());
        assertThrows(UnsupportedOperationException.class, () -> fromMutable.values().set(0, 9));
    }
}
