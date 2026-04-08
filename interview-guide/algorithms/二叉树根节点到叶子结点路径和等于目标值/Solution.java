package 二叉树根节点到叶子结点路径和等于目标值;

import common.ds.TreeNode;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

class Solution {
    // 结果集：存储所有有效路径
    List<List<Integer>> result = new ArrayList<>();

    // 当前路径：存储遍历过程中的路径
    LinkedList<Integer> path = new LinkedList<>();

    /**
     * 找出所有从根节点到叶子节点路径和等于 targetSum 的路径
     *
     * @param root      二叉树根节点
     * @param targetSum 目标和
     * @return 所有满足条件的路径
     */
    public List<List<Integer>> pathSum(TreeNode root, int targetSum) {
        dfs(root, targetSum);
        return result;
    }

    /**
     * 前序遍历 + 回溯
     *
     * @param node      当前节点
     * @param remainSum 剩余目标和（还需要凑多少）
     */
    public void dfs(TreeNode node, int remainSum) {
        if (node == null) {
            return;
        }

        // 1.当前节点加入path
        path.add(node.val);
        remainSum -= node.val;

        // 2.判断是否是叶子节点
        if (node.left == null && node.right == null) {
            // 是叶子节点且路径和等于目标值
            if (remainSum == 0) {
                // 找到一条有效路径，加入结果集
                // 注意：要 new 一个新的 ArrayList，不能直接加 path 引用
                result.add(new ArrayList<>(path));
            }
        } else {
            // 不是叶子节点，继续递归左右子树
            dfs(node.left, remainSum);
            dfs(node.right, remainSum);
        }

        // 3.撤销回溯
        path.removeLast();
    }

    public static void main(String[] args) {
        Solution solution = new Solution();

        // 构建测试用例 1：题目示例
        //       5
        //      / \
        //     4   8
        //    /   / \
        //   11  13  4
        //  /  \    / \
        // 7    2  5   1
        TreeNode root1 = buildTestTree1();
        List<List<Integer>> result1 = solution.pathSum(root1, 22);
        System.out.println("测试 1：目标和 = 22");
        System.out.println("结果：" + result1);
        System.out.println("预期：[[5,4,11,2], [5,8,4,5]]");
    }

    private static TreeNode buildTestTree1() {
        TreeNode root = new TreeNode(5);
        root.left = new TreeNode(4);
        root.right = new TreeNode(8);
        root.left.left = new TreeNode(11);
        root.left.left.left = new TreeNode(7);
        root.left.left.right = new TreeNode(2);
        root.right.left = new TreeNode(13);
        root.right.right = new TreeNode(4);
        root.right.right.left = new TreeNode(5);
        root.right.right.right = new TreeNode(1);
        return root;
    }
}
