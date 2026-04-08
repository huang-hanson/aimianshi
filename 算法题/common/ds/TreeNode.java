package common.ds;

/**
 * Definition for a binary tree node.
 * public class TreeNode {
 * int val;
 * TreeNode left;
 * TreeNode right;
 * TreeNode() {}
 * TreeNode(int val) { this.val = val; }
 * TreeNode(int val, TreeNode left, TreeNode right) {
 * this.val = val;
 * this.left = left;
 * this.right = right;
 * }
 * }
 */
public class TreeNode {

    public int val;
    /**
     * 左节点
     **/
    public TreeNode left;
    /**
     * 右节点
     **/
    public TreeNode right;

    TreeNode(){

    }

    public TreeNode(int val) {
        this.val = val;
    }

    public TreeNode(int val,TreeNode left,TreeNode right){
        this.val = val;
        this.left = left;
        this.right = right;
    }
}