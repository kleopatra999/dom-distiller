// Copyright 2014 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.dom_distiller.client;

import com.google.gwt.dom.client.Element;
import com.google.gwt.dom.client.MetaElement;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.dom.client.Style;
import com.google.gwt.dom.client.TableCellElement;
import com.google.gwt.dom.client.TableElement;
import com.google.gwt.dom.client.TableRowElement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Classifies a <table> element as layout or data type, based on the set of heuristics at
 * http://asurkov.blogspot.com/2011/10/data-vs-layout-table.html, with some modifications to suit
 * our distillation needs.
 */
public class TableClassifier {
    public enum Type {
        DATA,
        LAYOUT,
    }

    enum Reason {  // Default access qualifier to be accessible by tests.
        INSIDE_EDITABLE_AREA,
        ROLE_TABLE,
        ROLE_DESCENDANT,
        DATATABLE_0,
        SUMMARY_CAPTION_THEAD_TFOOT_COLGROUP_COL_TH,
        ABBR_HEADERS_SCOPE,
        ONLY_HAS_ABBR,
        NESTED_TABLE,
        LESS_EQ_1_ROW,
        LESS_EQ_1_COL,
        MORE_EQ_5_COLS,
        CELLS_HAVE_BORDER,
        DIFFERENTLY_COLORED_ROWS,
        MORE_EQ_20_ROWS,
        MORE_95_PERCENT_DOC_WIDTH,
        LESS_EQ_10_CELLS,
        EMBED_OBJECT_APPLET_IFRAME,
        DEFAULT,
        UNKNOWN,
    }
    static Reason sReason;

    private static final Set<String> sARIATableRoles;
    private static final Set<String> sARIATableDescendantRoles;
    private static final Set<String> sARIARoles;
    static {
        // ARIA roles for table - http://www.w3.org/TR/wai-aria/roles#widget_roles_header.
        sARIATableRoles = new HashSet<String>();
        sARIATableRoles.add("grid");
        sARIATableRoles.add("treegrid");

        // ARIA roles for descendants of table:
        sARIATableDescendantRoles = new HashSet<String>();
        // - http://www.w3.org/TR/wai-aria/roles#widget_roles_header.
        sARIATableDescendantRoles.add("gridcell");
        // - http://www.w3.org/TR/wai-aria/roles#document_structure_roles_header.
        sARIATableDescendantRoles.add("columnheader");
        sARIATableDescendantRoles.add("row");
        sARIATableDescendantRoles.add("rowgroup");
        sARIATableDescendantRoles.add("rowheader");

        // ARIA landmark roles, applicable to both table and its descendents
        // - http://www.w3.org/TR/wai-aria/roles#landmark_roles_header.
        sARIARoles = new HashSet<String>();
        sARIARoles.add("application");
        sARIARoles.add("banner");
        sARIARoles.add("complementary");
        sARIARoles.add("contentinfo");
        sARIARoles.add("form");
        sARIARoles.add("main");
        sARIARoles.add("navigation");
        sARIARoles.add("search");
    }

    public static Type table(TableElement t) {
        sReason = Reason.UNKNOWN;

        // The following heuristics are dropped from said url:
        // - table created by CSS display style is layout table, because we only handle actual
        //   <table> elements.
 
        // 1) Table inside editable area is layout table, different from said url because we ignore
        //    editable areas during distillation.
        Element parent = t.getParentElement();
        while (parent != null) {
            if (parent.hasTagName("INPUT") ||
                    parent.getAttribute("contenteditable").equalsIgnoreCase("true")) {
                return logAndReturn(Reason.INSIDE_EDITABLE_AREA, "", Type.LAYOUT);
            }
            parent = parent.getParentElement();
        }
 
        // 2) Table having role="presentation" is layout table.
        String tableRole = t.getAttribute("role").toLowerCase();
        if (tableRole.equals("presentation")) {
            return logAndReturn(Reason.ROLE_TABLE, "_" + tableRole, Type.LAYOUT);
        }

        // 3) Table having ARIA table-related roles is data table.
        if (sARIATableRoles.contains(tableRole) || sARIARoles.contains(tableRole)) {
            return logAndReturn(Reason.ROLE_TABLE, "_" + tableRole, Type.DATA);
        }

        // 4) Table having ARIA table-related roles in its descendants is data table. 
        // This may have deviated from said url if it only checks for <table> element but not its
        // descendants.
        List<Element> directDescendants = getDirectDescendants(t);
        for (Element e : directDescendants) {
            String role = e.getAttribute("role").toLowerCase();
            if (sARIATableDescendantRoles.contains(role) || sARIARoles.contains(role)) {
                return logAndReturn(Reason.ROLE_DESCENDANT, "_" + role, Type.DATA);
            }
        }
      
        // 5) Table having datatable="0" attribute is layout table.
        if (t.getAttribute("datatable").equals("0")) {
            return logAndReturn(Reason.DATATABLE_0, "", Type.LAYOUT);
        }
 
        // 6) Table having summary attribute or legitimate data table structures is data table:
        // a) table has <caption>, <thead>, <tfoot>, <colgroup>, <col>, or <th> elements
        if (t.hasAttribute("summary") ||
                t.getCaption() != null || t.getTHead() != null || t.getTFoot() != null ||
                hasElement(directDescendants, "COLGROUP") || hasElement(directDescendants, "COL") ||
                hasElement(directDescendants, "TH")) {
            return logAndReturn(Reason.SUMMARY_CAPTION_THEAD_TFOOT_COLGROUP_COL_TH, "", Type.DATA);
        }
 
        // Extract all <td> elements from direct descendants, for easier/faster multiple access.
        List<Element> directTDs = new ArrayList<Element>();
        for (Element e : directDescendants) {
            if (e.hasTagName("TD")) directTDs.add(e);
        }
 
        for (Element e : directTDs) {
            // b) table cell has abbr, headers, or scope attributes
            if (e.hasAttribute("abbr") || e.hasAttribute("headers") || e.hasAttribute("scope")) {
                return logAndReturn(Reason.ABBR_HEADERS_SCOPE, "", Type.DATA);
            }
            // c) table cell has <abbr> element as a single child element.
            NodeList<Element> children = e.getElementsByTagName("*");
            if (children.getLength() == 1 && children.getItem(0).hasTagName("ABBR")) {
                return logAndReturn(Reason.ONLY_HAS_ABBR, "", Type.DATA);
            }
        }
 
        // 7) Table having nested table(s) is layout table.
        if (hasNestedTables(t)) return logAndReturn(Reason.NESTED_TABLE, "", Type.LAYOUT);
 
        // 8) Table having only one row or column is layout table.
        NodeList<TableRowElement> rows = t.getRows();
        if (rows.getLength() <= 1) return logAndReturn(Reason.LESS_EQ_1_ROW, "", Type.LAYOUT);
        NodeList<TableCellElement> cols = rows.getItem(0).getCells();
        if (cols.getLength() <= 1) return logAndReturn(Reason.LESS_EQ_1_COL, "", Type.LAYOUT);
 
        // 9) Table having >=5 columns is data table.
        if (cols.getLength() >= 5) return logAndReturn(Reason.MORE_EQ_5_COLS, "", Type.DATA);
 
        // 10) Table having borders around cells is data table.
        for (Element e : directTDs) {
            String border = DomUtil.getComputedStyle(e).getBorderStyle();
            if (!border.isEmpty() && !border.equals("none") && !border.equals("hidden")) {
                return logAndReturn(Reason.CELLS_HAVE_BORDER, "_" + border, Type.DATA);
            }
        }
 
        // 11) Table having differently-colored rows is data table.
        String prevBackgroundColor = null;
        for (int i = 0; i < rows.getLength(); i++) {
            String color = DomUtil.getComputedStyle(rows.getItem(i)).getBackgroundColor();
            if (prevBackgroundColor == null) {
                prevBackgroundColor = color;
                continue;
            }
            if (!prevBackgroundColor.equalsIgnoreCase(color)) {
                return logAndReturn(Reason.DIFFERENTLY_COLORED_ROWS, "", Type.DATA);
            }
        }
       
        // 12) Table having >=20 rows is data table.
        if (rows.getLength() >= 20) return logAndReturn(Reason.MORE_EQ_20_ROWS, "", Type.DATA);
 
        // 13) Table occupying > 95% of document width without viewport meta is layout table;
        // viewport condition is not in said url, added here for typical mobile-optimized sites.
        Element docElement = t.getOwnerDocument().getDocumentElement();
        if ((double) t.getOffsetWidth() > 0.95 * (double) docElement.getOffsetWidth()) {
            boolean viewport = false;
            NodeList<Element> allMeta = docElement.getElementsByTagName("META");
            for (int i = 0; i < allMeta.getLength() && !viewport; i++) {
                MetaElement meta = MetaElement.as(allMeta.getItem(i));
                viewport = meta.getName().equalsIgnoreCase("viewport");
            }
            if (!viewport) return logAndReturn(Reason.MORE_95_PERCENT_DOC_WIDTH, "", Type.LAYOUT);
        }
 
        // 14) Table having <=10 cells is layout table.
        if (directTDs.size() <= 10) return logAndReturn(Reason.LESS_EQ_10_CELLS, "", Type.LAYOUT);
 
        // 15) Table containing <embed>, <object>, <applet> or <iframe> elements (typical
        //     advertisement elements) is layout table.
        for (Element e : directDescendants) {
            if (e.hasTagName("EMBED") || e.hasTagName("OBJECT") || e.hasTagName("APPLET") ||
                    e.hasTagName("IFRAME")) {
                return logAndReturn(Reason.EMBED_OBJECT_APPLET_IFRAME, "", Type.LAYOUT);
            }
        }
 
        // Otherwise, it's data table.
        return logAndReturn(Reason.DEFAULT, "", Type.DATA);
    }

    private static boolean hasNestedTables(Element t) {
        return t.getElementsByTagName("TABLE").getLength() > 0;
    }

    private static List<Element> getDirectDescendants(Element t) {
        List<Element> directDescendants = new ArrayList<Element>();
        NodeList<Element> allDescendants = t.getElementsByTagName("*");
        if (!hasNestedTables(t)) {
            for (int i = 0; i < allDescendants.getLength(); i++) {
                directDescendants.add(allDescendants.getItem(i));
            }
        } else {
            for (int i = 0; i < allDescendants.getLength(); i++) {
                // Check if the current element is a direct descendent of the |t| table element in
                // question, as opposed to being a descendent of a nested table in |t|.
                Element e = allDescendants.getItem(i);
                Element parent = e.getParentElement();
                while (parent != null) {
                    if (parent.hasTagName("TABLE")) {
                        if (parent == t) directDescendants.add(e);
                        break;
                    }
                    parent = parent.getParentElement();
                }
            }
        }
        return directDescendants;
    }

    private static boolean hasElement(List<Element> list, String tagname) {
        for (Element e : list) {
            if (e.hasTagName(tagname)) return true;
        }
        return false;
    }

    private static Type logAndReturn(Reason reason, String append, Type type) {
        if (DomDistiller.isLoggable(DomDistiller.DEBUG_LEVEL_VISIBILITY_INFO)) {
            LogUtil.logToConsole(reason + append + " -> " + type);
        }
        sReason = reason;
        return type;
    }
}