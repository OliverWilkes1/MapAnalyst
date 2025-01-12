package ika.mapanalyst;

import java.io.*;
import java.util.*;
import ika.geo.*;
import ika.geo.osm.Projector;
import ika.transformation.Transformation;
import ika.utils.NumberFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Manages all links.
 *
 * @author Adrian Weber and Bernhard Jenny
 */
public class LinkManager implements GeoSetSelectionChangeListener,
        GeoSetChangeListener, Serializable {

    private static final long serialVersionUID = -6411317472598414220L;

    /**
     * name of GeoSet that contains the points of the old map
     */
    public static final String OLD_POINTS_GEOSET_NAME = "old points";
    
    /**
     * name of GeoSet that contains the points of the new map
     */
    public static final String NEW_POINTS_GEOSET_NAME = "new points";
    
    /**
     * A list with all links.
     */
    private final Vector linksList;

    private final GeoSet oldPointsGeoSet;

    private final GeoSet newPointsGeoSet;

    private PointSymbol unlinkedPointSymbol;

    private PointSymbol linkedPointSymbol;

    private double[][] oldPointsHull;

    private double[][] newPointsHull;

    private static final int MIN_NBR_LINKED_POINTS_FOR_COMPUTATION = 5;

    /**
     * Constructs an LinkManager with an empty linksList.
     */
    public LinkManager() {
        linksList = new Vector();

        // PointSymbols
        unlinkedPointSymbol = new PointSymbol();
        unlinkedPointSymbol.setScaleInvariant(true);
        linkedPointSymbol = new PointSymbol();
        linkedPointSymbol.setScaleInvariant(true);
        linkedPointSymbol.setStrokeColor(new java.awt.Color(204, 0, 102));

        // GeoSets
        oldPointsGeoSet = new GeoSet();
        oldPointsGeoSet.setName(OLD_POINTS_GEOSET_NAME);
        newPointsGeoSet = new GeoSet();
        newPointsGeoSet.setName(NEW_POINTS_GEOSET_NAME);

        registerAsListener();
    }
    
    private void readObject(ObjectInputStream stream)
            throws IOException, ClassNotFoundException {

        // read this object
        stream.defaultReadObject();

        // register this object as listener
        registerAsListener();
    }

    private void serializePoint(GeoPoint geoPoint, DataOutputStream os)
            throws java.io.IOException {
        String name = geoPoint.getName();
        if (name == null) {
            name = "";
        }
        os.writeUTF(name);
        os.writeDouble(geoPoint.getX());
        os.writeDouble(geoPoint.getY());
        os.writeBoolean(geoPoint.isSelected());
    }

    private void serializeLinkedPoints(DataOutputStream os,
            boolean onlySelected)
            throws java.io.IOException {
        final int nbrLinks = getNumberLinks();

        if (onlySelected) {
            os.writeInt(getSelectedLinks().size());
        } else {
            os.writeInt(nbrLinks);
        }
        for (int i = 0; i < nbrLinks; ++i) {
            final Link link = (Link) linksList.get(i);

            // only serialize selected links if required
            if (onlySelected && !link.isSelected()) {
                continue;
            }

            String name = link.getName();
            if (name == null) {
                name = ""; // this should never happen
            }
            os.writeUTF(name);
            serializePoint(link.getPtOld(), os);
            serializePoint(link.getPtNew(), os);
        }
    }

    private void serializeUnlinkedPoints(DataOutputStream os,
            GeoSet geoSet,
            boolean onlySelected)
            throws java.io.IOException {
        final int nbrTotalPts = geoSet.getNumberOfChildren();
        final int nbrLinkedPts = getNumberLinks();
        final int nbrUnlinkedPts = nbrTotalPts - nbrLinkedPts;
        os.writeInt(nbrUnlinkedPts);
        for (int i = 0; i < nbrTotalPts; ++i) {
            GeoPoint geoPoint = (GeoPoint) geoSet.getGeoObject(i);
            Link link = getLink(geoPoint);
            if (link == null) {
                // only serialize selected points if requested
                if (onlySelected && !geoPoint.isSelected()) {
                    continue;
                }

                serializePoint(geoPoint, os);
            }
        }
    }

    public byte[] serializePoints(boolean onlySelected) {
        try {
            ByteArrayOutputStream bas = new ByteArrayOutputStream();
            GZIPOutputStream zip = new GZIPOutputStream(bas);
            BufferedOutputStream bos = new BufferedOutputStream(zip);
            DataOutputStream dos = new DataOutputStream(bos);
            serializeLinkedPoints(dos, onlySelected);
            serializeUnlinkedPoints(dos, oldPointsGeoSet, onlySelected);
            serializeUnlinkedPoints(dos, newPointsGeoSet, onlySelected);
            dos.close();
            return bas.toByteArray();
        } catch (IOException ex) {
            Logger.getLogger(LinkManager.class.getName()).log(Level.SEVERE, null, ex);
            return null;
        }
    }

    private GeoPoint deserializePoint(DataInputStream is)
            throws java.io.IOException {
        String name = is.readUTF();
        double x = is.readDouble();
        double y = is.readDouble();
        boolean selected = is.readBoolean();
        GeoPoint geoPoint = new GeoPoint(x, y);
        if (!"".equals(name)) {
            geoPoint.setName(name);
        }
        geoPoint.setSelected(selected);
        return geoPoint;
    }

    private void deserializeLinkedPoints(DataInputStream is)
            throws java.io.IOException {
        final int nbrLinks = is.readInt();
        for (int i = 0; i < nbrLinks; ++i) {
            String name = is.readUTF();
            if ("".equals(name)) {
                name = generateUniqueName("");
            }

            GeoPoint oldPoint = deserializePoint(is);
            GeoPoint newPoint = deserializePoint(is);
            oldPoint.setPointSymbol(linkedPointSymbol);
            newPoint.setPointSymbol(linkedPointSymbol);

            // First try creating a link. This is more likely to throw an 
            // exception than adding the points to the maps. An exception is
            // thrown, when there already exists a link with at least one
            // point with the same coordinates.
            addLink(oldPoint, newPoint, name, false);

            oldPointsGeoSet.addGeoObject(oldPoint);
            newPointsGeoSet.addGeoObject(newPoint);
        }
        updateConvexHull();
    }

    private void deserializeUnlinkedPoints(DataInputStream is, GeoSet geoSet)
            throws java.io.IOException {
        final int nbrPts = is.readInt();
        for (int i = 0; i < nbrPts; ++i) {
            GeoPoint geoPoint = deserializePoint(is);
            geoPoint.setPointSymbol(unlinkedPointSymbol);
            geoSet.addGeoObject(geoPoint);
        }
    }

    public void deserializePoints(byte[] b) throws java.io.IOException {
        try {
            ByteArrayInputStream bas = new ByteArrayInputStream(b);
            GZIPInputStream zip = new GZIPInputStream(bas);
            BufferedInputStream bis = new BufferedInputStream(zip);
            DataInputStream dis = new DataInputStream(bis);

            oldPointsGeoSet.suspendGeoSetChangeListeners();
            newPointsGeoSet.suspendGeoSetChangeListeners();
            deserializeLinkedPoints(dis);
            deserializeUnlinkedPoints(dis, oldPointsGeoSet);
            deserializeUnlinkedPoints(dis, newPointsGeoSet);
            dis.close();
        } finally {
            oldPointsGeoSet.activateGeoSetChangeListeners(null);
            newPointsGeoSet.activateGeoSetChangeListeners(null);
        }
    }

    private void registerAsListener() {
        oldPointsGeoSet.addGeoSetSelectionChangeListener(this);
        newPointsGeoSet.addGeoSetSelectionChangeListener(this);

        oldPointsGeoSet.addGeoSetChangeListener(this);
        newPointsGeoSet.addGeoSetChangeListener(this);
    }

    /**
     * Adds a new link to the linksList.
     *
     * @param oldPt The GeoPoint of the old map
     * @param newPt The GeoPoint of the new map
     * @param name The name of the new link
     * @param updateConvexHull If this flag is true, the convex hulls around the
     * points is updated.
     * @return The new link.
     */
    public Link addLink(GeoPoint oldPt, GeoPoint newPt, String name,
            boolean updateConvexHull) {

        // Make sure no points with the same coordinates already exist in the list.
        // Having links with identical coordinates leads to numerical problems
        // when computing the multiquadric interpolation for the distortion grid.
        // When exporting points to ASCII files, 6 digits after the decimal point are written.
        // Coordinates differing by less than 0.000001 are therefore considered as
        // being equal.
        final double COORD_TOLERANCE = 0.000001;

        final int nbrLinks = getNumberLinks();
        for (int i = 0; i < nbrLinks; ++i) {
            final Link link = (Link) linksList.get(i);
            if (oldPt.isPointClose(link.getPtOld(), COORD_TOLERANCE)
                    || newPt.isPointClose(link.getPtNew(), COORD_TOLERANCE)) {
                throw new IllegalArgumentException("A linked point with same "
                        + "coordinates already exists."
                        + "\nThe name of the conflicting point is \""
                        + link.getName() + "\"");
            }
        }

        // generate a unique name for the link
        name = generateUniqueName(name);

        // create the link and add it to the list of links.
        Link link = new Link(oldPt, newPt, name);
        link.setPointSymbol(linkedPointSymbol);
        linksList.add(link);

        // update the convex hull if required
        if (updateConvexHull) {
            updateConvexHull();
        }

        return link;
    }

    public void linkPointsByName() {
        GeoSet oldPts = getOldPointsGeoSet();
        GeoSet newPts = getNewPointsGeoSet();

        final int nbrOldPts = oldPts.getNumberOfChildren();
        final boolean updateConvexHull = false;
        for (int i = 0; i < nbrOldPts; ++i) {
            GeoPoint oldGeoPoint = (GeoPoint) (oldPts.getGeoObject(i));
            String name = oldGeoPoint.getName();
            GeoObject geoObject = newPts.getGeoObject(name);
            if (geoObject == null) {
                continue;
            }
            GeoPoint newGeoPoint = (GeoPoint) (geoObject);
            addLink(oldGeoPoint, newGeoPoint, name, updateConvexHull);
        }
        updateConvexHull();
    }

    /**
     * Checks if the specified name already exists in linksList.
     *
     * @param name the name which has to be checked
     * @return true if the specified name already exists
     */
    private boolean nameExists(String name) {
        for (int i = 0; i < linksList.size(); i++) {
            final Object obj = linksList.get(i);
            final Link parsedLink = (Link) obj;
            //System.out.println(parsedLink);
            if (parsedLink.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Generates an unique name for a link.
     *
     * @param baseName name from users input
     * @return an unique name that not yet exists in linksList
     */
    public String generateUniqueName(String baseName) {
        String newName = baseName;
        int j = 1;
        while (nameExists(newName)) {
            newName = baseName + "_" + j;
            j++;
        }
        return newName;
    }

    /**
     * Renames the selected link.
     *
     * @param newName the new name for the selected link
     */
    public void renameSelectedLink(String newName) {
        Link link = getSingleSelectedLink();
        if (link == null || newName == null) {
            return;
        }
        newName = generateUniqueName(newName);
        link.setName(newName);
    }

    /**
     * Returns all selected links in a Vector.
     *
     * @return the selected link
     */
    public Vector getSelectedLinks() {
        Vector selectedLinks = new Vector();
        for (int i = 0; i < linksList.size(); i++) {
            final Object obj = linksList.get(i);
            final Link parsedLink = (Link) obj;
            if (parsedLink.getPtOld().isSelected() || parsedLink.getPtNew().isSelected()) {
                selectedLinks.add(parsedLink);
            }
        }
        return selectedLinks;
    }

    public Link getSingleSelectedLink() {
        Link selectedLink = null;
        for (int i = 0; i < linksList.size(); i++) {
            final Link link = (Link) (linksList.get(i));
            if (link.getPtOld().isSelected() || link.getPtNew().isSelected()) {
                if (selectedLink != null) {
                    return null;
                }
                selectedLink = link;
            }
        }
        return selectedLink;
    }

    /**
     * Runs a search in linksList for the link with the specified name.
     *
     * @param name name of the searched link
     * @return the searched link
     */
    public Link searchLink(String name) {
        for (int i = 0; i < linksList.size(); i++) {
            final Object obj = linksList.get(i);
            if (obj instanceof Link) {
                final Link link = (Link) obj;
                if (link.getName().equals(name)) {
                    //System.out.println("found at index " + i + ":");
                    return link;
                }
            }
        }
        return null;
    }

    public GeoPoint searchPoint(String name, boolean oldMap) {
        final GeoSet parentGeoSet = oldMap ? oldPointsGeoSet : newPointsGeoSet;
        final int nbrPoints = parentGeoSet.getNumberOfChildren();
        for (int i = 0; i < nbrPoints; i++) {
            final GeoPoint geoPoint = (GeoPoint) parentGeoSet.getGeoObject(i);
            if (name.equals(geoPoint.getName())) {
                return geoPoint;
            }
        }
        return null;
    }

    /**
     * Search and select link by name.
     */
    public Link selectLink(String linkName) {
        // search the link
        Link link = searchLink(linkName);
        if (link == null) {
            return null;
        }

        // deselect all currently selected points
        oldPointsGeoSet.setSelected(false);

        // select the found link
        link.setSelected(true);

        return link;
    }

    public void setLinkSelection(int id, boolean selected) {
        Link link = getLink(id);
        if (link != null) {
            link.setSelected(selected);
        }
    }

    private void selectUnlinkedPoints(GeoSet geoSet) {
        final int nbrPts = geoSet.getNumberOfChildren();
        for (int i = 0; i < nbrPts; ++i) {
            GeoPoint geoPoint = (GeoPoint) geoSet.getGeoObject(i);
            Link link = getLink(geoPoint);
            geoPoint.setSelected(link == null);
        }
        geoSet.informGeoSetSelectionChangeListeners(geoSet);
    }

    public void selectUnlinkedPoints() {
        selectUnlinkedPoints(oldPointsGeoSet);
        selectUnlinkedPoints(newPointsGeoSet);
    }

    /**
     * Returns a String with all link names and their coordinates.
     *
     * @param separator The string used to separate two coordinates (e.g. ", ").
     * @param addHeader If true, start with a header line.
     * @param oldMap
     * @param oldToNewTrans If not null, the points of the old map are
     * transformed to the new map and the length and azimuth of the vectors
     * connecting a pair of points in the new map are written to the file.
     * @return a string
     */
    public String getReport(String separator,
            boolean addHeader,
            GeoImage oldMap,
            Transformation oldToNewTrans) {

        final int NUMBER_LENGTH = 20;
        final int NBR_DECIMALS = 6;

        final boolean oldPointsInPixels = oldMap != null;

        StringBuilder str = new StringBuilder(1028);
        String newline = System.getProperty("line.separator");
        if (separator == null) {
            separator = ",\t";
        }

        // find longest name
        int maxNameLength = 10; // 10 is minimum
        for (int i = 0; i < linksList.size(); i++) {
            final Link link = (Link) (linksList.get(i));
            final int nameLength = link.getName().length();
            if (nameLength > maxNameLength) {
                maxNameLength = nameLength;
            }
        }

        // header
        if (addHeader) {
            str.append("Link Name "); // 10 characters
            for (int i = 10; i < maxNameLength; i++) {
                str.append(" ");
            }
            str.append("\t");
            if (oldPointsInPixels) {
                str.append("X Old Map [px]       \t");
                str.append("Y Old Map [px]       \t");
            } else {
                str.append("X Old Map [m]        \t");
                str.append("Y Old Map [m]        \t");
            }
            str.append("X New Map            \t");
            str.append("Y New Map            \t");
            if (oldToNewTrans != null) {
                str.append("Vector Length        \t");
                str.append("Vector Azimuth       ");
            }
            str.append(newline);
        }

        for (int i = 0; i < linksList.size(); i++) {
            final Link link = (Link) (linksList.get(i));
            String linkName = link.getName();
            str.append(linkName);
            for (int j = linkName.length(); j < maxNameLength; j++) {
                str.append(" ");
            }

            str.append(separator);
            GeoPoint oldPoint = link.getPtOld();

            // Don't just use a standard DecimalFormat since localized characters for
            // the decimal separator could be used. We require a dot (".").
            if (oldPointsInPixels) {
                // transform from meters to image pixels relative to the 
                // top-left corner
                double x = (oldPoint.getX() - oldMap.getX()) / oldMap.getPixelSizeX();
                double y = (oldMap.getY() - oldPoint.getY()) / oldMap.getPixelSizeY();
                str.append(NumberFormatter.format(x, NUMBER_LENGTH, NBR_DECIMALS));
                str.append(separator);

                str.append(NumberFormatter.format(y, NUMBER_LENGTH, NBR_DECIMALS));
                str.append(separator);
            } else {
                str.append(NumberFormatter.format(oldPoint.getX(), NUMBER_LENGTH, NBR_DECIMALS));
                str.append(separator);

                str.append(NumberFormatter.format(oldPoint.getY(), NUMBER_LENGTH, NBR_DECIMALS));
                str.append(separator);
            }

            GeoPoint newPoint = link.getPtNew();
            double newX = newPoint.getX();
            double newY = newPoint.getY();

            str.append(NumberFormatter.format(newX, NUMBER_LENGTH, NBR_DECIMALS));
            str.append(separator);
            str.append(NumberFormatter.format(newY, NUMBER_LENGTH, NBR_DECIMALS));
            str.append(separator);

            if (oldToNewTrans != null) {
                GeoPoint transformedOldPt = oldToNewTrans.transform(oldPoint);
                double dx = transformedOldPt.getX() - newX;
                double dy = transformedOldPt.getY() - newY;
                double l = Math.hypot(dx, dy);
                double azimuth = -Math.toDegrees(Math.atan2(dy, dx)) + 90.;
                if (azimuth < 0) {
                    azimuth += 360;
                }
                str.append(NumberFormatter.format(l, NUMBER_LENGTH, NBR_DECIMALS));
                str.append(separator);
                str.append(NumberFormatter.format(azimuth, NUMBER_LENGTH, NBR_DECIMALS));
                str.append(separator);
            }

            str.append(newline);
        }
        return str.toString();
    }

    /**
     * Returns a copy of the linked points.
     *
     * @param projector projector.OSM2Intermediate() is used to transform the
     * new points from OSM to the intermediate coordinate system used for
     * creating distortion visualizations.
     * @return An array of two arrays. The first array contains points in the
     * old map, the second array contains point in the new map.
     */
    public double[][][] getLinkedPointsCopy(Projector projector) {
        double[][] oldPoints = new double[linksList.size()][2];
        double[][] newPoints = new double[linksList.size()][2];
        for (int i = 0; i < oldPoints.length; i++) {
            final Object obj = linksList.get(i);
            final Link link = (Link) obj;
            oldPoints[i][0] = link.getPtOld().getX();
            oldPoints[i][1] = link.getPtOld().getY();
            newPoints[i][0] = link.getPtNew().getX();
            newPoints[i][1] = link.getPtNew().getY();
        }

        if (projector != null) {
            projector.OSM2Intermediate(newPoints, newPoints);
        }
        return new double[][][]{oldPoints, newPoints};
    }

    public boolean hasEnoughLinkedPointsForComputation() {
        return getNumberLinks() >= LinkManager.MIN_NBR_LINKED_POINTS_FOR_COMPUTATION;
    }

    public int getMinNbrOfLinkedPointsForComputation() {
        return LinkManager.MIN_NBR_LINKED_POINTS_FOR_COMPUTATION;
    }

    /**
     * Returns the single GeoPoint that is selected either in the old or the new
     * map.
     *
     * @param inOldMap Specify whether to search in the old or the new map.
     * @return The only GeoPoint that is selected. If multiple GeoPoints are
     * selected this method returns null.
     */
    public GeoPoint getSingleSelectedGeoPoint(boolean inOldMap) {
        GeoSet geoSet;
        if (inOldMap) {
            geoSet = oldPointsGeoSet;
        } else {
            geoSet = newPointsGeoSet;
        }

        GeoObject obj = geoSet.getSingleSelectedGeoObject();
        if (obj == null || !(obj instanceof GeoPoint)) {
            return null;
        }
        return (GeoPoint) obj;
    }

    /**
     * Creates a new link from to selected points. Only creates a new link if
     * exactly one link is selected in the new map and one link in the old map.
     *
     * @param linkName The name of the new link. The new link is not guaranteed
     * to use this name. The name is modified in case it is already used by
     * another link.
     * @return The new link
     */
    public Link createLinkFromSelectedPoints(String linkName) {
        if (linkName == null) {
            return null;
        }

        // search the two selected points
        GeoPoint oldSingleSelectedGeoPoint = getSingleSelectedGeoPoint(true);
        GeoPoint newSingleSelectedGeoPoint = getSingleSelectedGeoPoint(false);
        if (oldSingleSelectedGeoPoint == null
                || newSingleSelectedGeoPoint == null) {
            return null;
        }

        // build the link
        Link link = addLink(oldSingleSelectedGeoPoint,
                newSingleSelectedGeoPoint, linkName, true);

        return link;
    }

    /**
     * Returns the number of links in linksList.
     *
     * @return the number of links in linksList
     */
    public int getNumberLinks() {
        return linksList.size();
    }

    /**
     * Deletes the selected links in linksList. The concerned points will not be
     * deleted from the map.
     */
    public void deleteSelectedLinks() {
        Vector selectedLinks = getSelectedLinks();
        int nbrDeletedLinks = 0;
        for (int i = 0; i < selectedLinks.size(); i++) {
            Link link = (Link) (selectedLinks.get(i));
            link.setPointSymbol(unlinkedPointSymbol);
            linksList.remove(link);
            nbrDeletedLinks++;
        }
        if (nbrDeletedLinks > 0) {
            updateConvexHull();
        }
    }

    /**
     * Deletes all links and all points.
     */
    public void deletePointsAndLinks(boolean onlySelected) {
        if (onlySelected) {
            // remove all selected links from linksList
            final int nbrLinks = getNumberLinks();
            for (int i = nbrLinks - 1; i >= 0; i--) {
                Link link = (Link) linksList.get(i);
                if (link.isSelected()) {
                    linksList.remove(i);
                }
            }

            // remove all points from the map
            oldPointsGeoSet.removeSelectedGeoObjects();
            newPointsGeoSet.removeSelectedGeoObjects();

            updateConvexHull();
        } else {
            linksList.clear();
            oldPointsGeoSet.removeAllGeoObjects();
            newPointsGeoSet.removeAllGeoObjects();
            updateConvexHull();
        }
    }

    /**
     * Scales the points in the old maps if they are linked. Useful to convert
     * between different units (e.g. pixels to meter).
     *
     * @param scale The scale factor to apply.
     */
    public void scaleLinkedPointsInOldMap(double scale) {
        final int nbrLinks = getNumberLinks();
        for (int i = nbrLinks - 1; i >= 0; i--) {
            Link link = (Link) linksList.get(i);
            link.getPtOld().scale(scale);
        }
    }

    /**
     * Searches and returns a Link that references a certain GeoPoint.
     *
     * @param geoPoint
     * @return the link with the specified GeoPoint
     */
    public Link getLink(GeoPoint geoPoint) {
        for (int i = 0; i < linksList.size(); i++) {
            final Link link = (Link) (linksList.get(i));
            if (link.getPtNew() == geoPoint || link.getPtOld() == geoPoint) {
                return link;
            }
        }
        return null;
    }

    public Link getLink(int id) {
        try {
            return (Link) (linksList.get(id));
        } catch (IndexOutOfBoundsException exc) {
            return null;
        }
    }

    @Override
    public String toString() {
        return getReport(null, true, null, null);
    }

    /**
     * Event handler that is called whenever the selection state of a GeoObject
     * in oldPointsGeoSet or newPointsGeoSet changes.
     *
     * @param geoSet The GeoSet that changed.
     */
    @Override
    public void geoSetSelectionChanged(GeoSet geoSet) {

        // update selection of points
        for (int i = 0; i < linksList.size(); i++) {
            final Link link = (Link) (linksList.get(i));

            // select the partner point in the other GeoSet.
            if (geoSet == oldPointsGeoSet) {
                link.setSelected(link.getPtOld().isSelected());
            } else {
                link.setSelected(link.getPtNew().isSelected());
            }
        }

        // we just changed the selection state of GeoPoints.
        // Trigger geoSetChanged calls so that other listeners are informed of
        // our change.
        if (geoSet == oldPointsGeoSet) {
            newPointsGeoSet.informGeoSetSelectionChangeListeners(this);
        } else {
            oldPointsGeoSet.informGeoSetSelectionChangeListeners(this);
        }
    }

    @Override
    public void geoSetChanged(GeoSet geoSet) {
        updateConvexHull();
    }

    public void updateConvexHull() {
        // update convex hull around points in old and new map
        final int nbrPts = linksList.size();
        java.util.Vector oldPoints = new java.util.Vector();
        java.util.Vector newPoints = new java.util.Vector();
        for (int i = 0; i < nbrPts; i++) {
            final Link link = (Link) linksList.get(i);

            final GeoPoint oldGeoPoint = link.getPtOld();
            final GeoPoint newGeoPoint = link.getPtNew();
            oldPoints.addElement(new convexhull.CPoint((float) oldGeoPoint.getX(),
                    (float) oldGeoPoint.getY()));
            newPoints.addElement(new convexhull.CPoint((float) newGeoPoint.getX(),
                    (float) newGeoPoint.getY()));
        }
        java.util.Vector oldHullVec = convexhull.GrahamScan.computeHull(oldPoints);
        java.util.Vector newHullVec = convexhull.GrahamScan.computeHull(newPoints);
        oldPointsHull = convexhull.HullConverter.hullArray(oldHullVec);
        newPointsHull = convexhull.HullConverter.hullArray(newHullVec);
    }

    public java.awt.Color getUnlinkedPointColor() {
        return linkedPointSymbol.getStrokeColor();
    }

    public void setUnlinkedPointColor(java.awt.Color color) {
        unlinkedPointSymbol.setStrokeColor(color);
    }

    public java.awt.Color getLinkedPointColor() {
        return linkedPointSymbol.getStrokeColor();
    }

    public void setLinkedPointColor(java.awt.Color color) {
        linkedPointSymbol.setStrokeColor(color);
    }

    public PointSymbol getUnlinkedPointSymbol() {
        return unlinkedPointSymbol;
    }

    public void setUnlinkedPointSymbol(PointSymbol unlinkedPointSymbol) {
        this.unlinkedPointSymbol = unlinkedPointSymbol;
    }

    public PointSymbol getLinkedPointSymbol() {
        return linkedPointSymbol;
    }

    public void setLinkedPointSymbol(PointSymbol linkedPointSymbol) {
        this.linkedPointSymbol = linkedPointSymbol;
    }

    public GeoSet getOldPointsGeoSet() {
        return oldPointsGeoSet;
    }

    public GeoSet getNewPointsGeoSet() {
        return newPointsGeoSet;
    }

    public double[][] getOldPointsHull() {
        return oldPointsHull;
    }

    public double[][] getNewPointsHull() {
        return newPointsHull;
    }

}
