
package org.micromanager.projector;

import ij.gui.EllipseRoi;
import ij.gui.OvalRoi;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.process.FloatPolygon;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mmcorej.CMMCore;
import org.micromanager.Studio;
import org.micromanager.internal.utils.ReportingUtils;
import org.micromanager.projector.internal.Mapping;
import org.micromanager.projector.internal.ProjectorControlForm;
import org.micromanager.projector.internal.Utils;
import org.micromanager.projector.internal.devices.Galvo;
import org.micromanager.projector.internal.devices.SLM;
import org.micromanager.propertymap.MutablePropertyMapView;

/**
 *
 * @author Nico
 */
public abstract class ProjectorActions {
   
   /**
    * Creates a ProjectionDevice from the first SLM (or Galvo if no SLM is 
    * present) found by the Micro-Manager Core.
    * 
    * @param studio
    * @return First ProjectionDevice found
    */
   public static ProjectionDevice getProjectionDevice(Studio studio) {
      ProjectionDevice dev = null;
      CMMCore core = studio.core();
      String slm = core.getSLMDevice();
      String galvo = core.getGalvoDevice();

      if (slm.length() > 0) {
         dev = new SLM(studio, core, 20);
      } else if (galvo.length() > 0) {
         dev = new Galvo(studio, core);
      } 
      return dev;
   }
   
   /**
    *
    * @param app
    * @param dev
    * @return
    */
   public static Map<Polygon, AffineTransform> loadMapping(Studio app, 
            ProjectionDevice dev) {
      MutablePropertyMapView settings = app.profile().getSettings(
              ProjectorControlForm.class);
      return Mapping.loadMapping(app.core(), dev, settings);
   }
   
    /**
    * Sets the exposure time for the photo-targeting device.
    * @param dev ProjectionDevice to be used
    * @param intervalUs  new exposure time in micros
    */
   public static void setExposure(ProjectionDevice dev, double intervalUs) {
      long previousExposure = dev.getExposure();
      long newExposure = (long) intervalUs;
      if (previousExposure != newExposure) {
         dev.setExposure(newExposure);
     }
   }
   
   
    /**
    * Illuminate a spot at position x,y.
    * @param dev ProjectionDevice to be used
    * @param x coordinate of ProjectionDevice
    * @param y coordinate of ProjectionDevice
    */
   public static void displaySpot(ProjectionDevice dev, double x, double y) {
      if (x >= dev.getXMinimum() && x < (dev.getXRange() + dev.getXMinimum())
            && y >= dev.getYMinimum() && y < (dev.getYRange() + dev.getYMinimum())) {
         dev.displaySpot(x, y);
      }
   }
   
   /**
    * Activates a spot at the center of the Galvo/SLM range
    * @param dev ProjectionDevice to be used
    */
   public static void displayCenterSpot(ProjectionDevice dev) {
      double x = dev.getXRange() / 2 + dev.getXMinimum();
      double y = dev.getYRange() / 2 + dev.getYMinimum();
      dev.displaySpot(x, y);
   }
   
   /**
    * Returns ROIs, transformed by the current mapping.
    * @param rois Array of ImageJ Rois to be converted
    * @param mapping 
    * @return list of Rois converted into Polygons
    * 
    */
   public static List<FloatPolygon> transformROIs(Roi[] rois, 
           Map<Polygon, AffineTransform> mapping) {
      return ProjectorActions.transformRoiPolygons(roisAsPolygons(rois), mapping);
   }
   
    /**
    * Transform the Roi polygons with the given nonlinear mapping.
    * @param roiPolygons
    * @param mapping
    * @return 
    */
   public static List<FloatPolygon> transformRoiPolygons( 
           Polygon[] roiPolygons, Map<Polygon, AffineTransform> mapping) {
      ArrayList<FloatPolygon> transformedROIs = new ArrayList<FloatPolygon>();
      for (Polygon roiPolygon : roiPolygons) {
         FloatPolygon targeterPolygon = new FloatPolygon();
         try {
            Point2D targeterPoint;
            for (int i = 0; i < roiPolygon.npoints; ++i) {
               Point2D.Double imagePoint = new Point2D.Double(
                       roiPolygon.xpoints[i], roiPolygon.ypoints[i]);
               // targeterPoint = transformAndMirrorPoint(mapping, imagePoint);
               targeterPoint = transformPoint(mapping, imagePoint);
               if (targeterPoint == null) {
                  throw new Exception();
               }
               targeterPolygon.addPoint( (float) targeterPoint.getX(), 
                       (float) targeterPoint.getY() );
            }
            transformedROIs.add(targeterPolygon);
         } catch (Exception ex) {
            ReportingUtils.showError(ex);
            break;
         }
      }
      return transformedROIs;
   }
   
   
   
   /** 
    * Converts an array of ImageJ Rois to an array of Polygons.
    * Handles EllipseRois and compound Point ROIs.
    * @param rois ImageJ Rois to be converted to Polygons
    * @return Polygons representing the input ImageJ Rois
   */
   public static Polygon[] roisAsPolygons(Roi[] rois) {
      Roi[] cleanROIs = homogenizeROIs(rois);
      List<Polygon> roiPolygons = new ArrayList<Polygon>();
      for (Roi roi : cleanROIs) {
         roiPolygons.add(asPolygon(roi));
      }
      return roiPolygons.toArray(new Polygon[0]);
   }
   
   
   /**
    * Transform a point in camera coordinates to projector coordinates, 
    * given the mapping, which is a Map of polygon cells to AffineTransforms.
    * @param mapping Map of cells (quadrants) on the camera image, that 
    *    each contain the affineTransform appropriate to that cell
    * @param pt Point to be transformed from camera to projector coordinates 
    * @return Point in projector coordinates
   */
   public static Point2D.Double transformPoint(Map<Polygon, AffineTransform> mapping, 
           Point2D.Double pt) {
      Set<Polygon> set = mapping.keySet();
      // First find out if the given point is inside a cell, and if so,
      // transform it with that cell's AffineTransform.
      for (Polygon poly : set) {
         if (poly.contains(pt)) {
            return (Point2D.Double) mapping.get(poly).transform(pt, null);
         }
      }
      // The point isn't inside any cell, so search for the closest cell
      // and use the AffineTransform from that.
      double minDistance = Double.MAX_VALUE;
      Polygon bestPoly = null;
      for (Polygon poly : set) {
         double distance = Utils.meanPosition2D(Utils.getVertices(poly)).distance(pt.x, pt.y);
         if (minDistance > distance) {
            bestPoly = poly;
            minDistance = distance;
         }
      }
      if (bestPoly == null) {
         throw new RuntimeException("Unable to map point to device.");
      }
      return (Point2D.Double) mapping.get(bestPoly).transform(pt, null);
   }
   
   public static void transformAndSetMask(Map<Polygon, AffineTransform> mapping, 
           ProjectionDevice dev, byte[] inputImage, int width, int height) {
      if (! (dev instanceof SLM) ) {
         // how do we ler rge caller know
         return;
      }
      if (inputImage.length != width * height) {
         // how do we let the caller know?
         return;
      }
      SLM slm = (SLM) dev;
      int slmWidth = (int) slm.getXRange();
      int slmHeight = (int) slm.getYRange();
      byte[] outputImage = new byte[slmWidth * slmHeight];
      for (int x = 0; x < width; x++) {
         for (int y = 0; y < height; y++) {
            if (inputImage[x + y * width] > 0) {
               Point2D.Double rp = transformPoint(mapping, new Point2D.Double(x, y));
               int xt = (int) rp.x;
               int yt = (int) rp.y;
               if (0 <= xt && xt < slmWidth && 0 <= yt && yt < slmHeight) {
                  // TODO: is this the correct value??
                  outputImage[xt + yt * slmHeight] = 127;
               }
            }
         }
      }
      slm.displaySLMImage(outputImage);
   }
   
   /************* Private methods **************/
      
   // Converts an ROI to a Polygon.
   private static Polygon asPolygon(Roi roi) {
      if ((roi.getType() == Roi.POINT)
               || (roi.getType() == Roi.FREEROI)
               || (roi.getType() == Roi.POLYGON)
            || (roi.getType() == Roi.RECTANGLE)) {
         return roi.getPolygon();
      } else {
         throw new RuntimeException("Can't use this type of ROI.");
      }
   }
   
    // We can't handle Ellipse Rois and compound PointRois directly.
   private static Roi[] homogenizeROIs(Roi[] rois) {
      List<Roi> roiList = new ArrayList<Roi>();
      for (Roi roi : rois) {
         switch (roi.getType()) {
            case Roi.POINT:
               Polygon poly = ((PointRoi) roi).getPolygon();
               for (int i = 0; i < poly.npoints; ++i) {
                  roiList.add(new PointRoi(
                          poly.xpoints[i],
                          poly.ypoints[i]));
               }  break;
            case Roi.OVAL:
               roiList.add(asEllipseRoi((OvalRoi) roi));
               break;
            default:
               roiList.add(roi);
               break;
         }
      }
      return roiList.toArray(new Roi[roiList.size()]);
   }
   
    // Convert an OvalRoi to an EllipseRoi.
   private static Roi asEllipseRoi(OvalRoi roi) {
      Rectangle bounds = roi.getBounds();
      double aspectRatio = bounds.width / (double) bounds.height;
      if (aspectRatio < 1) {
         return new EllipseRoi(bounds.x + bounds.width / 2,
               bounds.y,
               bounds.x + bounds.width / 2,
               bounds.y + bounds.height,
               aspectRatio);
      } else {
         return new EllipseRoi(bounds.x,
               bounds.y + bounds.height / 2,
               bounds.x + bounds.width,
               bounds.y + bounds.height / 2,
               1 / aspectRatio);
      }
   }
 
}