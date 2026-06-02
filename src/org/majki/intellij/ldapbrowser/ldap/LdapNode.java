package org.majki.intellij.ldapbrowser.ldap;

import com.intellij.util.xmlb.annotations.Transient;
import com.intellij.openapi.diagnostic.Logger;
import org.apache.directory.api.ldap.model.cursor.CursorException;
import org.apache.directory.api.ldap.model.cursor.EntryCursor;
import org.apache.directory.api.ldap.model.cursor.SearchCursor;
import org.apache.directory.api.ldap.model.entry.Attribute;
import org.apache.directory.api.ldap.model.entry.Entry;
import org.apache.directory.api.ldap.model.entry.Value;
import org.apache.directory.api.ldap.model.exception.LdapException;
import org.apache.directory.api.ldap.model.message.Referral;
import org.apache.directory.api.ldap.model.message.SearchRequest;
import org.apache.directory.api.ldap.model.message.SearchRequestImpl;
import org.apache.directory.api.ldap.model.message.SearchResultEntry;
import org.apache.directory.api.ldap.model.message.SearchScope;
import org.apache.directory.api.ldap.model.name.Dn;
import org.apache.directory.ldap.client.api.LdapConnection;
import org.apache.directory.ldap.client.template.exception.LdapRuntimeException;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;


public class LdapNode implements Serializable {

    private static final Logger LOGGER = Logger.getInstance(LdapNode.class);

    public static final String OBJECTCLASS_TOP = "top";
    public static final String OBJECTCLASS_PERSON = "person";
    public static final String OBJECTCLASS_INETORGPERSON = "inetOrgPerson";
    public static final String OBJECTCLASS_ORGANIZATIONAL_PERSON = "organizationalPerson";
    public static final String OBJECTCLASS_ORGANIZATIONAL_UNIT = "organizationalUnit";
    public static final String OBJECTCLASS_DOMAIN = "domain";
    public static final String OBJECTCLASS_GROUP_OF_UNIQUE_NAMES = "groupOfUniqueNames";
    public static final String OBJECTCLASS_GROUP = "group";
    public static final String OBJECTCLASS_META_SCHEMA = "metaSchema";
    public static final String OBJECTCLASS_ACCOUNT = "account";

    public static final String OBJECTCLASS_ATTRIBUTE_NAME = "objectclass";
    public static final String OBJECTCLASS_ATTRIBUTE_NAME_UP = "objectClass";

    public static final String USERPASSWORD_ATTRIBUTE_NAME = "userpassword";
    public static final String USERPASSWORD_ATTRIBUTE_NAME_UP = "userPassword";

    public static final String NAMING_CONTEXT_ATTRIBUTE_NAME = "namingcontexts";
    public static final String NAMING_CONTEXT_ATTRIBUTE_NAME_UP = "namingContexts";
    public static final String MEMBER_ATTRIBUTE_NAME = "member";
    public static final String UNIQUE_MEMBER_ATTRIBUTE_NAME = "uniqueMember";

    private static final String DEFAULT_FILTER = "(objectclass=*)";
    private static final String DEFAULT_SEARCH_ATTRIBUTE = "*";
    private static final int MAX_CHILDREN_PER_NODE = 500;
    private static final int MAX_REFERRAL_DEPTH = 5;

    @Transient
    private final LdapConnectionInfo ldapConnectionInfo;

    private String dn;
    private String rdn;
    private LdapNode parent;
    private List<LdapNode> children;
    private List<LdapAttribute> attributes;
    private List<String> objectClassValues;
    private LdapObjectClass topObjectClass;
    private boolean childrenTruncated;

    private LdapNode(LdapConnectionInfo ldapConnectionInfo, LdapNode parent, LdapObjectClass topObjectClass, String dn, String rdn, List<LdapAttribute> attributes) {
        this.ldapConnectionInfo = ldapConnectionInfo;
        this.dn = dn;
        this.rdn = rdn;
        this.parent = parent;
        this.topObjectClass = topObjectClass;
        this.attributes = attributes;
        this.children = null;
        this.childrenTruncated = false;
        this.objectClassValues = null;
    }

    public static LdapNode createRoot(LdapConnectionInfo ldapConnectionInfo) throws LdapException {
        return createRoot(ldapConnectionInfo, "");
    }

    public static LdapNode createRoot(LdapConnectionInfo ldapConnectionInfo, String baseDn) throws LdapException {
        LdapObjectClass topObjectClass = LdapObjectClass.getTop(ldapConnectionInfo);
        return new LdapNode(ldapConnectionInfo, null, topObjectClass, baseDn, LdapUtil.getTopDn(baseDn), Collections.emptyList());
    }

    public static LdapNode createNew(LdapConnectionInfo ldapConnectionInfo, LdapNode parent) throws LdapException {
        LdapObjectClass topObjectClass = LdapObjectClass.getTop(ldapConnectionInfo);
        return new LdapNode(null, parent, topObjectClass, "", "", new ArrayList<>());
    }

    public static List<LdapNode> search(LdapConnectionInfo ldapConnectionInfo, String baseDn, String filter, SearchScope scope, int sizeLimit) throws LdapException {
        LdapObjectClass topObjectClass = LdapObjectClass.getTop(ldapConnectionInfo);
        List<LdapNode> results = new ArrayList<>();
        Set<String> seenDns = new HashSet<>();
        Set<String> followedReferrals = new HashSet<>();
        String searchBase = baseDn == null ? "" : baseDn.trim();
        String searchFilter = filter == null || filter.trim().isEmpty() ? DEFAULT_FILTER : filter.trim();
        int limit = sizeLimit <= 0 ? MAX_CHILDREN_PER_NODE : sizeLimit;
        int skippedReferrals = searchConnection(
            ldapConnectionInfo,
            ldapConnectionInfo.getLdapConnection(),
            topObjectClass,
            null,
            searchBase,
            searchFilter,
            scope,
            limit,
            results,
            seenDns,
            followedReferrals,
            0
        );
        LOGGER.info("LDAP search: returned " + results.size() + " entr" + (results.size() == 1 ? "y" : "ies") + ", skipped " + skippedReferrals + " referral" + (skippedReferrals == 1 ? "" : "s"));
        return results;
    }

    private static int searchConnection(
        LdapConnectionInfo ldapConnectionInfo,
        LdapConnection connection,
        LdapObjectClass topObjectClass,
        LdapNode parent,
        String searchBase,
        String searchFilter,
        SearchScope scope,
        int limit,
        List<LdapNode> results,
        Set<String> seenDns,
        Set<String> followedReferrals,
        int referralDepth
    ) throws LdapException {
        SearchRequest searchRequest = new SearchRequestImpl();
        searchRequest.setBase(new Dn(searchBase));
        searchRequest.setFilter(searchFilter);
        searchRequest.setScope(scope);
        searchRequest.setSizeLimit(limit);
        searchRequest.addAttributes(DEFAULT_SEARCH_ATTRIBUTE);

        LOGGER.info("LDAP search: base=\"" + searchBase + "\", scope=" + scope + ", filter=" + searchFilter + ", limit=" + limit + ", followReferrals=true, referralDepth=" + referralDepth);
        int skippedReferrals = 0;
        try (SearchCursor cursor = connection.search(searchRequest)) {
            while (cursor.next()) {
                if (cursor.isReferral()) {
                    Referral referral = cursor.getReferral();
                    for (String referralUrl : referral.getLdapUrls()) {
                        skippedReferrals += followReferral(
                            ldapConnectionInfo,
                            topObjectClass,
                            parent,
                            referralUrl,
                            searchBase,
                            searchFilter,
                            scope,
                            limit,
                            results,
                            seenDns,
                            followedReferrals,
                            referralDepth
                        );
                    }
                    skippedReferrals++;
                    continue;
                }
                if (!cursor.isEntry()) {
                    continue;
                }
                if (results.size() >= limit) {
                    break;
                }
                Entry entry = ((SearchResultEntry) cursor.get()).getEntry();
                String normalizedDn = entry.getDn().getName().toLowerCase(Locale.ROOT);
                if (!seenDns.add(normalizedDn)) {
                    continue;
                }
                List<LdapAttribute> attributes = new ArrayList<>();
                for (Attribute attribute : entry.getAttributes()) {
                    mapLdapAttributes(attributes, attribute);
                }
                results.add(new LdapNode(ldapConnectionInfo, parent, topObjectClass, entry.getDn().getName(), entry.getDn().getRdn().getName(), attributes));
            }
        } catch (CursorException | IOException e) {
            LOGGER.warn("LDAP search failed for base=\"" + searchBase + "\", scope=" + scope + ", filter=" + searchFilter, e);
            throw new LdapException("Search cursor error: " + rootCauseMessage(e), e);
        }
        return skippedReferrals;
    }

    private static int followReferral(
        LdapConnectionInfo ldapConnectionInfo,
        LdapObjectClass topObjectClass,
        LdapNode parent,
        String referralUrl,
        String originalBase,
        String originalFilter,
        SearchScope originalScope,
        int limit,
        List<LdapNode> results,
        Set<String> seenDns,
        Set<String> followedReferrals,
        int referralDepth
    ) throws LdapException {
        if (referralDepth >= MAX_REFERRAL_DEPTH || results.size() >= limit || !followedReferrals.add(referralUrl)) {
            LOGGER.info("LDAP search: skipping referral URL " + referralUrl);
            return 0;
        }

        LdapReferralTarget target = LdapReferralTarget.parse(referralUrl, ldapConnectionInfo, originalBase, originalFilter, originalScope);
        LOGGER.info("LDAP search: following referral URL " + referralUrl + " as base=\"" + target.baseDn + "\", scope=" + target.scope + ", filter=" + target.filter);
        try (LdapConnection referralConnection = ldapConnectionInfo.openReferralConnection(target.host, target.port, target.ssl)) {
            return searchConnection(
                ldapConnectionInfo,
                referralConnection,
                topObjectClass,
                parent,
                target.baseDn,
                target.filter,
                target.scope,
                limit,
                results,
                seenDns,
                followedReferrals,
                referralDepth + 1
            );
        } catch (IOException e) {
            throw new LdapException("Could not close referral connection", e);
        }
    }

    private static String rootCauseMessage(Throwable throwable) {
        Throwable rootCause = throwable;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        String message = rootCause.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = throwable.getMessage();
        }
        if (message == null || message.trim().isEmpty()) {
            return rootCause.getClass().getSimpleName();
        }
        return rootCause.getClass().getSimpleName() + ": " + message;
    }

    private static class LdapReferralTarget {
        private final String host;
        private final int port;
        private final boolean ssl;
        private final String baseDn;
        private final String filter;
        private final SearchScope scope;

        private LdapReferralTarget(String host, int port, boolean ssl, String baseDn, String filter, SearchScope scope) {
            this.host = host;
            this.port = port;
            this.ssl = ssl;
            this.baseDn = baseDn;
            this.filter = filter;
            this.scope = scope;
        }

        private static LdapReferralTarget parse(String referralUrl, LdapConnectionInfo fallback, String originalBase, String originalFilter, SearchScope originalScope) throws LdapException {
            try {
                URI uri = URI.create(referralUrl);
                boolean ssl = "ldaps".equalsIgnoreCase(uri.getScheme());
                String host = uri.getHost() == null || uri.getHost().trim().isEmpty() ? fallback.getHost() : uri.getHost();
                int port = uri.getPort() > 0 ? uri.getPort() : (ssl ? 636 : 389);
                String baseDn = referralBaseDn(uri, originalBase);
                String[] queryParts = uri.getRawQuery() == null ? new String[0] : uri.getRawQuery().split("\\?", -1);
                SearchScope scope = queryParts.length > 1 && !queryParts[1].trim().isEmpty() ? referralScope(queryParts[1]) : originalScope;
                String filter = queryParts.length > 2 && !queryParts[2].trim().isEmpty() ? decode(queryParts[2]) : originalFilter;
                return new LdapReferralTarget(host, port, ssl, baseDn, filter, scope);
            } catch (IllegalArgumentException e) {
                throw new LdapException("Could not parse referral URL: " + referralUrl, e);
            }
        }

        private static String referralBaseDn(URI uri, String originalBase) {
            String rawPath = uri.getRawPath();
            if (rawPath == null || rawPath.trim().isEmpty() || "/".equals(rawPath)) {
                return originalBase;
            }
            return decode(rawPath.startsWith("/") ? rawPath.substring(1) : rawPath);
        }

        private static SearchScope referralScope(String value) {
            if ("base".equalsIgnoreCase(value)) {
                return SearchScope.OBJECT;
            }
            if ("one".equalsIgnoreCase(value)) {
                return SearchScope.ONELEVEL;
            }
            return SearchScope.SUBTREE;
        }

        private static String decode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
    }

    public static void valueModifier(LdapAttribute.Value value, String newValue) {
        value.setValue(newValue);
    }

    public static void valueModifier(LdapAttribute.Value value, byte[] newValue) {
        value.setValue(newValue);
    }

    public LdapConnectionInfo getLdapConnectionInfo() {
        return ldapConnectionInfo;
    }

    public Optional<LdapAttribute> getAttributeByName(String name) {
        return attributes.stream()
            .filter(attribute -> attribute.name().equalsIgnoreCase(name))
            .findFirst();
    }

    public void refresh() throws LdapException {
        Set<LdapAttribute> notSeenAttributes = new HashSet<>(attributes);
        objectClassValues = null;
        try (EntryCursor cursor = getConnection().search(dn, DEFAULT_FILTER, SearchScope.OBJECT, DEFAULT_SEARCH_ATTRIBUTE)) {
            if (cursor.next()) {
                Entry entry = cursor.get();
                for (Attribute attribute : entry.getAttributes()) {
                    Optional<LdapAttribute> foundLdapAttribute = getAttributeByName(attribute.getId());
                    if (foundLdapAttribute.isPresent()) {
                        LdapAttribute ldapAttribute = foundLdapAttribute.get();
                        notSeenAttributes.remove(ldapAttribute);
                        List<LdapAttribute.Value> values = ldapAttribute.values();
                        values.clear();
                        addValuesFromAttribute(attribute, values);
                    } else {
                        mapLdapAttributes(attributes, attribute);
                    }
                }
            }
            attributes.removeAll(notSeenAttributes);
        } catch (CursorException | IOException e) {
            throw new LdapException("Cursor error", e);
        }
    }

    private static void addValuesFromAttribute(Attribute attribute, List<LdapAttribute.Value> values) {
        attribute.forEach(value -> values.add(new LdapAttribute.Value(value.isNull(), value.isHumanReadable(), value.getString(), value.getBytes())));
    }

    private void readRootDSN() throws LdapException {
        LOGGER.info("LDAP browse: reading Root DSE naming contexts");
        Entry rootDse = getConnection().getRootDse(NAMING_CONTEXT_ATTRIBUTE_NAME);
        if (rootDse == null) {
            throw new LdapException("No root dse has been found");
        }
        int count = 0;
        for (Attribute attribute : rootDse.getAttributes()) {
            if (attribute.getId().equalsIgnoreCase(NAMING_CONTEXT_ATTRIBUTE_NAME)) {
                for (Value value : attribute) {
                    LdapNode node = new LdapNode(ldapConnectionInfo, this, topObjectClass, value.getString(), value.getString(), new ArrayList<>());
                    node.refresh();
                    children.add(node);
                    count++;
                }
            }
        }
        LOGGER.info("LDAP browse: Root DSE returned " + count + " naming context(s)");
    }

    private void searchChildren() throws LdapException {
        children = new ArrayList<>();
        childrenTruncated = false;

        if (dn == null || dn.trim().isEmpty()) {
            readRootDSN();
        } else {
            int skippedReferrals = searchConnection(
                ldapConnectionInfo,
                getConnection(),
                topObjectClass,
                this,
                dn,
                DEFAULT_FILTER,
                SearchScope.ONELEVEL,
                MAX_CHILDREN_PER_NODE,
                children,
                new HashSet<>(),
                new HashSet<>(),
                0
            );
            removeSelfFromChildren();
            if (children.isEmpty() && isGroupLike()) {
                LOGGER.info("LDAP browse: no child entries for group dn=\"" + dn + "\"; resolving member attributes");
                searchGroupMembers();
            }
            if (children.isEmpty()) {
                LOGGER.info("LDAP browse: no one-level child entries for dn=\"" + dn + "\"; trying bounded subtree fallback");
                skippedReferrals += searchConnection(
                    ldapConnectionInfo,
                    getConnection(),
                    topObjectClass,
                    this,
                    dn,
                    DEFAULT_FILTER,
                    SearchScope.SUBTREE,
                    MAX_CHILDREN_PER_NODE,
                    children,
                    new HashSet<>(),
                    new HashSet<>(),
                    0
                );
                removeSelfFromChildren();
            }
            childrenTruncated = children.size() >= MAX_CHILDREN_PER_NODE;
            LOGGER.info("LDAP browse: child search returned " + children.size() + " entr" + (children.size() == 1 ? "y" : "ies") + " for dn=\"" + dn + "\", skipped " + skippedReferrals + " referral" + (skippedReferrals == 1 ? "" : "s") + (childrenTruncated ? " before hitting the local limit" : ""));
        }

        children.sort(Comparator.comparing(LdapNode::getRdn));
    }

    private void removeSelfFromChildren() {
        children.removeIf(child -> child.getDn().equalsIgnoreCase(dn));
    }

    private boolean isGroupLike() {
        return isInstanceOf(OBJECTCLASS_GROUP) || isInstanceOf(OBJECTCLASS_GROUP_OF_UNIQUE_NAMES);
    }

    public boolean isBrowsableContainer() {
        return !isInstanceOf(OBJECTCLASS_PERSON)
            && !isInstanceOf(OBJECTCLASS_ORGANIZATIONAL_PERSON)
            && !isInstanceOf("user");
    }

    private void searchGroupMembers() throws LdapException {
        List<String> memberDns = new ArrayList<>();
        getAttributeByName(MEMBER_ATTRIBUTE_NAME)
            .ifPresent(attribute -> attribute.values().stream().map(LdapAttribute.Value::asString).forEach(memberDns::add));
        getAttributeByName(UNIQUE_MEMBER_ATTRIBUTE_NAME)
            .ifPresent(attribute -> attribute.values().stream().map(LdapAttribute.Value::asString).forEach(memberDns::add));

        Set<String> requestedDns = new HashSet<>();
        Set<String> resultDns = new HashSet<>();
        for (String memberDn : memberDns) {
            if (children.size() >= MAX_CHILDREN_PER_NODE || memberDn == null || memberDn.trim().isEmpty() || !requestedDns.add(memberDn.toLowerCase(Locale.ROOT))) {
                continue;
            }
            searchConnection(
                ldapConnectionInfo,
                getConnection(),
                topObjectClass,
                this,
                memberDn,
                DEFAULT_FILTER,
                SearchScope.OBJECT,
                MAX_CHILDREN_PER_NODE,
                children,
                resultDns,
                new HashSet<>(),
                0
            );
        }
    }

    private static void mapLdapAttributes(List<LdapAttribute> attributes, Attribute attribute) {
        List<LdapAttribute.Value> values = new ArrayList<>();
        addValuesFromAttribute(attribute, values);
        int index = OBJECTCLASS_ATTRIBUTE_NAME.equalsIgnoreCase(attribute.getId()) ? 0 : attributes.size();
        attributes.add(index, new LdapAttribute(attribute.getId(), attribute.getUpId(), attribute.isHumanReadable(), values));
    }

    private void extractObjectClassValues() {
        objectClassValues = new ArrayList<>();
        for (LdapAttribute attribute : attributes) {
            if (OBJECTCLASS_ATTRIBUTE_NAME.equalsIgnoreCase(attribute.name())) {
                objectClassValues.addAll(attribute.values().stream().map(LdapAttribute.Value::asString).collect(Collectors.toList()));
                break;
            }
        }
    }

    public LdapConnection getConnection() {
        return ldapConnectionInfo.getLdapConnection();
    }

    public String getDn() {
        return dn;
    }

    public String getRdn() {
        return rdn;
    }

    public LdapNode getParent() {
        return parent;
    }

    public LdapObjectClass getTopObjectClass() {
        return topObjectClass;
    }

    public List<LdapNode> getChildren() throws LdapException {
        if (children == null) {
            searchChildren();
        }
        return children;
    }

    public List<LdapAttribute> getAttributes() {
        return attributes;
    }

    public int getChildCount() throws LdapException {
        return getChildren().size();
    }

    public boolean isChildrenTruncated() {
        return childrenTruncated;
    }

    public int getMaxChildrenPerNode() {
        return MAX_CHILDREN_PER_NODE;
    }

    public void refreshWithChildren() throws LdapException {
        refresh();
        searchChildren();
    }

    private List<String> getObjectClassValues() {
        if (objectClassValues == null) {
            extractObjectClassValues();
        }
        return objectClassValues;
    }

    public boolean isInstanceOf(String objectClass) {
        if (objectClass == null) {
            return false;
        }
        for (String objectClassValue : getObjectClassValues()) {
            if (objectClassValue.equalsIgnoreCase(objectClass.trim())) {
                return true;
            }
        }
        return false;
    }

    public Set<LdapObjectClass> getObjectClasses() {
        Set<LdapObjectClass> objectClasses = new HashSet<>();
        LdapObjectClass topObjectClass = getTopObjectClass();
        for (String objectClassValue : getObjectClassValues()) {
            LdapObjectClass loc = topObjectClass.getByName(objectClassValue);
            if (loc != null) {
                objectClasses.add(loc);
            }
        }
        return objectClasses;
    }

    public Set<LdapObjectClassAttribute> getObjectClassAttributes() {
        return getObjectClassAttributes(false);
    }

    public Set<LdapObjectClassAttribute> getObjectClassAttributes(boolean mustOnly) {
        Set<LdapObjectClassAttribute> objectClassAttributes = new HashSet<>();
        for (LdapObjectClass ldapObjectClass : getObjectClasses()) {
            objectClassAttributes.addAll(ldapObjectClass.getObjectClassAttributesWithInherited(mustOnly));
        }
        return objectClassAttributes;
    }

    public boolean containsAttributeWithValue(String attributeName, String value) {
        return attributes.stream()
            .filter(attribute -> attribute.name().equalsIgnoreCase(attributeName))
            .flatMap(attribute -> attribute.values().stream())
            .anyMatch(attributeValue -> attributeValue.asString().equals(value));
    }

    public boolean containsAttribute(String attributeName) {
        return getAttributeByName(attributeName).isPresent();
    }

    // TODO: data structure modification only
    public Set<LdapObjectClass> addObjectClass(LdapObjectClass objectClass) {
        Set<LdapObjectClass> allObjectClasses = objectClass.getAllSuperObjectClasses();
        allObjectClasses.add(objectClass);

        Set<LdapObjectClassAttribute> requiredAttributes = objectClass.getObjectClassAttributesWithInherited(true);

        for (LdapObjectClass oc : allObjectClasses) {
            if (!containsAttributeWithValue(OBJECTCLASS_ATTRIBUTE_NAME, oc.getName())) {
                getAttributeByName(OBJECTCLASS_ATTRIBUTE_NAME).orElseGet(() -> {
                    LdapAttribute ldapAttribute = new LdapAttribute(OBJECTCLASS_ATTRIBUTE_NAME, OBJECTCLASS_ATTRIBUTE_NAME_UP, true, new ArrayList<>());
                    attributes.add(ldapAttribute);
                    return ldapAttribute;
                }).values().add(new LdapAttribute.Value(false, true, oc.getName(), oc.getName().getBytes()));
            }
        }

        requiredAttributes.stream()
            .filter(requiredAttribute -> !containsAttribute(requiredAttribute.getName()))
            .map(requiredAttribute -> new LdapAttribute(
                requiredAttribute.getName().toLowerCase(),
                requiredAttribute.getName(),
                true, new ArrayList<>(Collections.singletonList(
                new LdapAttribute.Value(false, true, "", "".getBytes())))))
            .forEach(attributes::add);

        extractObjectClassValues();

        return allObjectClasses;
    }

    // TODO: data structure modification only
    public Set<LdapObjectClass> removeObjectClass(LdapObjectClass objectClass) {
        Set<LdapObjectClass> removedObjectClasses = new HashSet<>();
        LdapAttribute objectClassAttribute = getAttributeByName(OBJECTCLASS_ATTRIBUTE_NAME)
            .orElseThrow(() -> new LdapRuntimeException(new LdapException("Could not find attribute " + OBJECTCLASS_ATTRIBUTE_NAME)));

        List<String> objectClassNames = new ArrayList<>();
        for (LdapAttribute.Value value : objectClassAttribute.values()) {
            objectClassNames.add(value.asString());
        }

        removedObjectClasses.add(objectClass);
        objectClassNames.remove(objectClass.getName());

        for (LdapObjectClass subObjectClass : objectClass.getAllSubObjectClasses()) {
            objectClassNames.remove(subObjectClass.getName());
            removedObjectClasses.add(subObjectClass);
        }

        attributes.clear();
        for (String objectClassName : objectClassNames) {
            addObjectClass(getTopObjectClass().getByName(objectClassName));
        }

        extractObjectClassValues();

        return removedObjectClasses;
    }

}
