package javaBasics;

import java.util.*;

public class ArrayListVsLinkedListTest {
    public static void main(String[] args) {
        int size = 100000;

        // 1. 随机访问性能测试
        System.out.println("=== 随机访问测试 ===");
        testRandomAccess(size);

        // 2. 尾部添加性能测试
        System.out.println("\n=== 尾部添加测试 ===");
        testAddAtEnd(size);

        // 3. 中间插入性能测试
        System.out.println("\n=== 中间插入测试 ===");
        testInsertAtMiddle(size);

        // 4. 中间删除性能测试
        System.out.println("\n=== 中间删除测试 ===");
        testDeleteAtMiddle(size);
    }

    private static void testRandomAccess(int size) {
        ArrayList<Integer> arrayList = new ArrayList<>();
        LinkedList<Integer> linkedList = new LinkedList<>();

        for (int i = 0; i < size; i++) {
            arrayList.add(i);
            linkedList.add(i);
        }

        long start = System.nanoTime();
        for (int i = 0; i < size; i++) {
            arrayList.get(i);
        }
        System.out.println("ArrayList get: " + (System.nanoTime() - start) / 1_000_000 + "ms");

        start = System.nanoTime();
        for (int i = 0; i < size; i++) {
            linkedList.get(i);
        }
        System.out.println("LinkedList get: " + (System.nanoTime() - start) / 1_000_000 + "ms");
    }

    private static void testInsertAtMiddle(int size) {
        ArrayList<Integer> arrayList = new ArrayList<>();
        LinkedList<Integer> linkedList = new LinkedList<>();

        for (int i = 0; i < size; i++) {
            arrayList.add(i);
            linkedList.add(i);
        }

        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            arrayList.add(size / 2, i);
        }
        System.out.println("ArrayList 中间插入： " + (System.nanoTime() - start) / 1_000_000 + "ms");

        start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            linkedList.add(size / 2, i);
        }
        System.out.println("LinkedList 中间插入： " + (System.nanoTime() - start) / 1_000_000 + "ms");
    }

    private static void testDeleteAtMiddle(int size) {
        ArrayList<Integer> arrayList = new ArrayList<>();
        LinkedList<Integer> linkedList = new LinkedList<>();

        for (int i = 0; i < size; i++) {
            arrayList.add(i);
            linkedList.add(i);
        }

        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            arrayList.remove(size / 2);
        }
        System.out.println("ArrayList 中间删除：" + (System.nanoTime() - start) / 1_000_000 + "ms");

        start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            linkedList.remove(size / 2);
        }
        System.out.println("LinkedList 中间删除：" + (System.nanoTime() - start) / 1_000_000 + "ms");
    }

    private static void testAddAtEnd(int size) {
        ArrayList<Integer> arrayList = new ArrayList<>();
        LinkedList<Integer> linkedList = new LinkedList<>();

        long start = System.nanoTime();
        for (int i = 0; i < size; i++) {
            arrayList.add(i);
        }
        System.out.println("ArrayList 尾部添加：" + (System.nanoTime() - start) / 1_000_000 + "ms");

        start = System.nanoTime();
        for (int i = 0; i < size; i++) {
            linkedList.add(i);
        }
        System.out.println("LinkedList 尾部添加：" + (System.nanoTime() - start) / 1_000_000 + "ms");
    }
}