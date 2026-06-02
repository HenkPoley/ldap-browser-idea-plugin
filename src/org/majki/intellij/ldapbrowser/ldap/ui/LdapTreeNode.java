package org.majki.intellij.ldapbrowser.ldap.ui;

import com.intellij.openapi.util.IconLoader;
import org.apache.directory.api.ldap.model.exception.LdapException;
import org.jetbrains.annotations.Nullable;
import org.majki.intellij.ldapbrowser.editor.LdapNodeVirtualFile;
import org.majki.intellij.ldapbrowser.ldap.LdapConnectionInfo;
import org.majki.intellij.ldapbrowser.ldap.LdapNode;

import javax.swing.*;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreeNode;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Collectors;

public class LdapTreeNode extends LdapConnectionInfoTreeNode {

    private LdapNode node;
    private LdapNodeVirtualFile file;
    private boolean leaf;

    LdapTreeNode(LdapConnectionInfo info, MutableTreeNode parent, LdapNode node) {
        super(info, parent);
        this.node = node;
        this.file = new LdapNodeVirtualFile(this);
        this.leaf = false;
    }

    public static LdapTreeNode searchResult(LdapConnectionInfo info, LdapNode node) {
        return new LdapTreeNode(info, null, node);
    }

    private TreeNode createChildNode(LdapNode node) {
        return new LdapTreeNode(getConnectionInfo(), this, node);
    }

    @Override
    public TreeNode getChildAt(int childIndex) {
        List<TreeNode> children = childrenList();
        if (childIndex >= 0 && childIndex < children.size()) {
            return children.get(childIndex);
        }
        return new LdapPlaceholderTreeNode("Tree model changed. Refresh this LDAP entry.");
    }

    @Override
    public int getChildCount() {
        if (!node.isBrowsableContainer()) {
            leaf = true;
            return 0;
        }
        int childCount = childrenList().size();
        leaf = childCount == 0;
        return childCount;
    }

    @Override
    public int getIndex(TreeNode node) {
        if (node instanceof LdapTreeNode) {
            LdapNode ldapNode = ((LdapTreeNode) node).getLdapNode();
            try {
                return this.node.getChildren().indexOf(ldapNode);
            } catch (LdapException e) {
                LdapErrorHandler.handleError(e, "Cannot get index of LDAP node: " + ldapNode.getDn());
                return -1;
            }
        } else {
            return -1;
        }
    }

    @Override
    public boolean isLeaf() {
        return !node.isBrowsableContainer() || leaf;
    }

    public List<TreeNode> childrenList() {
        ArrayList<TreeNode> childLdapTreeNodes = new ArrayList<>();
        if (!node.isBrowsableContainer()) {
            leaf = true;
            return childLdapTreeNodes;
        }
        List<LdapNode> children = null;
        try {
            children = node.getChildren();
        } catch (LdapException e) {
            LdapErrorHandler.handleError(e, "Could not get children");
        }
        if (children != null) {
            childLdapTreeNodes.addAll(children.stream().map(this::createChildNode).collect(Collectors.toList()));
        }
        if (node.isChildrenTruncated()) {
            childLdapTreeNodes.add(new LdapPlaceholderTreeNode("More than " + node.getMaxChildrenPerNode() + " entries found. Narrow the Base DN to browse this branch."));
        }
        leaf = childLdapTreeNodes.isEmpty();
        return childLdapTreeNodes;
    }

    @Override
    public Enumeration<TreeNode> children() {
        return Collections.enumeration(childrenList());
    }

    public LdapNode getLdapNode() {
        return node;
    }

    @Nullable
    @Override
    public Icon getIcon() {
        if (node.isInstanceOf(LdapNode.OBJECTCLASS_DOMAIN)) {
            return IconLoader.getIcon("/images/domain.png");
        } else if (node.isInstanceOf(LdapNode.OBJECTCLASS_PERSON)) {
            return IconLoader.getIcon("/images/person.png");
        } else if (node.isInstanceOf(LdapNode.OBJECTCLASS_GROUP) || node.isInstanceOf(LdapNode.OBJECTCLASS_GROUP_OF_UNIQUE_NAMES)) {
            return IconLoader.getIcon("/images/group.png");
        } else if (getAllowsChildren()) {
            return IconLoader.getIcon("/images/node.png");
        } else {
            return IconLoader.getIcon("/images/entry.png");
        }
    }

    public LdapNodeVirtualFile getFile() {
        return file;
    }

    @Override
    public String toString() {
        if (node.isInstanceOf(LdapNode.OBJECTCLASS_DOMAIN)) {
            return node.getDn();
        } else {
            return node.getRdn();
        }
    }
}
