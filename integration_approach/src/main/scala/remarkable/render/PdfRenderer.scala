package remarkable.render

import org.apache.pdfbox.pdmodel.{PDDocument, PDPage, PDPageContentStream}
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState

import java.awt.Color
import java.nio.file.Path

/** Renders a sequence of reMarkable stroke pages into a multi-page PDF.
  *
  * Coordinate system:
  *   reMarkable canvas: device-specific dimensions read from SceneInfo block
  *     - reMarkable 2:    1404 × 1872 units
  *     - reMarkable Move: 1620 × 2160 units (A4-sized device)
  *   V6 origin: x=0 is the left edge, y=0 is the top (top-left origin)
  *   PDF A4 points: 595.28 × 841.89 pt (bottom-left origin)
  *
  * We scale the canvas to fit A4, flipping the Y axis.
  */
object PdfRenderer:

  // Fallback canvas dimensions if SceneInfo block is absent
  // reMarkable 2 values; Move files should always have SceneInfo.
  val FallbackWidth  = 1404
  val FallbackHeight = 1872

  // Target PDF page size
  val PageWidth:  Float = PDRectangle.A4.getWidth   // 595.28 pt
  val PageHeight: Float = PDRectangle.A4.getHeight  // 841.89 pt

  /** Renders a list of raw .rm page byte arrays into a single PDF at `outPath`.
    *
    * Pages with parse errors are skipped with a warning written to stderr.
    */
  def render(rmPages: List[Array[Byte]], outPath: Path): Unit =
    val doc = PDDocument()
    try
      if rmPages.isEmpty then
        // Produce a single blank page rather than an invalid 0-page PDF
        doc.addPage(PDPage(PDRectangle.A4))
      else
        rmPages.foreach { rmBytes =>
          val page = PDPage(PDRectangle.A4)
          doc.addPage(page)
          try
            val rmPage = RmParser.parse(rmBytes)
            renderPage(doc, page, rmPage)
          catch
            case e: RmParseError =>
              System.err.println(s"[WARN] Skipping page due to parse error: ${e.getMessage}")
            case e: Exception =>
              System.err.println(s"[WARN] Skipping page due to unexpected error: ${e.getMessage}")
        }

      doc.save(outPath.toFile)
    finally
      doc.close()

  // -------------------------------------------------------------------------
  // Page rendering
  // -------------------------------------------------------------------------

  private def renderPage(doc: PDDocument, page: PDPage, rmPage: RmPage): Unit =
    // Use canvas dimensions from the file; fall back to rM2 defaults
    val canvasW = if rmPage.canvasWidth  > 0 then rmPage.canvasWidth.toFloat  else FallbackWidth.toFloat
    val canvasH = if rmPage.canvasHeight > 0 then rmPage.canvasHeight.toFloat else FallbackHeight.toFloat
    if rmPage.canvasWidth > 0 then
      System.err.println(s"[INFO] Canvas from SceneInfo: ${rmPage.canvasWidth} × ${rmPage.canvasHeight}")

    // Log actual coordinate ranges from the stroke data
    val allPoints = rmPage.layers.flatMap(_.strokes).flatMap(_.points)
    if allPoints.nonEmpty then
      val minX = allPoints.map(_.x).min
      val maxX = allPoints.map(_.x).max
      val minY = allPoints.map(_.y).min
      val maxY = allPoints.map(_.y).max
      System.err.println(f"[INFO] Stroke coord ranges: x=[$minX%.1f, $maxX%.1f]  y=[$minY%.1f, $maxY%.1f]")

    val scaleX = PageWidth  / canvasW
    val scaleY = PageHeight / canvasH

    // The v6 coordinate system is centred horizontally: x=0 is the midpoint of
    // the canvas. Confirmed from data: canvas=820, x range ≈ [-410, +410].
    // Shift by canvasW/2 to convert to a left-edge origin before scaling.
    val xOffset = canvasW / 2.0f

    val cs = PDPageContentStream(doc, page)
    try
      rmPage.layers.foreach { layer =>
        layer.strokes.foreach { stroke =>
          renderStroke(cs, stroke, scaleX, scaleY, xOffset, penWidthMultiplier(stroke.pen))
        }
      }
    finally
      cs.close()

  // -------------------------------------------------------------------------
  // Stroke rendering
  // -------------------------------------------------------------------------

  private def renderStroke(
      cs: PDPageContentStream,
      stroke: RmStroke,
      scaleX: Float,
      scaleY: Float,
      xOffset: Float,
      widthMult: Double,
  ): Unit =
    if stroke.points.size < 2 then return

    val (r, g, b, _) = strokeColor(stroke.color)
    cs.setStrokingColor(Color(r, g, b))

    cs.setLineJoinStyle(1)
    cs.setLineCapStyle(1)

    val baseWidth = (stroke.brushSize * scaleX * widthMult).toFloat
    cs.setLineWidth(baseWidth.max(0.3f))

    val points = stroke.points
    val p0 = toPageCoords(points.head, scaleX, scaleY, xOffset)
    cs.moveTo(p0._1, p0._2)

    points.tail.foreach { pt =>
      val (px, py) = toPageCoords(pt, scaleX, scaleY, xOffset)
      cs.lineTo(px, py)
    }

    cs.stroke()

  // -------------------------------------------------------------------------
  // Coordinate transformation
  // -------------------------------------------------------------------------

  /** Converts reMarkable canvas coordinates to PDF points (bottom-left origin).
    *
    * X: origin is centred (x=0 is the midpoint), so shift by +xOffset = canvasW/2
    *    before scaling to map the range [-canvasW/2, +canvasW/2] → [0, PageWidth].
    * Y: origin is at the top, flip for PDF's bottom-left origin.
    */
  private def toPageCoords(pt: RmPoint, scaleX: Float, scaleY: Float, xOffset: Float): (Float, Float) =
    val px = (pt.x + xOffset) * scaleX
    val py = PageHeight - (pt.y * scaleY)
    (px, py)

  // -------------------------------------------------------------------------
  // Pen / color helpers
  // -------------------------------------------------------------------------

  private def strokeColor(color: StrokeColor): (Float, Float, Float, Float) =
    color match
      case StrokeColor.Black   => (0.0f,  0.0f,  0.0f,  1.0f)
      case StrokeColor.Grey    => (0.5f,  0.5f,  0.5f,  1.0f)
      case StrokeColor.White   => (1.0f,  1.0f,  1.0f,  1.0f)
      case StrokeColor.Yellow  => (1.0f,  0.95f, 0.0f,  0.4f)
      case StrokeColor.Green   => (0.0f,  0.9f,  0.0f,  0.4f)
      case StrokeColor.Pink    => (1.0f,  0.4f,  0.7f,  0.4f)
      case StrokeColor.Blue    => (0.0f,  0.3f,  0.9f,  1.0f)
      case StrokeColor.Red     => (0.9f,  0.0f,  0.0f,  1.0f)
      case StrokeColor.Unknown => (0.0f,  0.0f,  0.0f,  1.0f)

  private def penWidthMultiplier(pen: PenType): Double =
    pen match
      case PenType.Highlighter1 | PenType.Highlighter2 => 5.0
      case PenType.Brush1       | PenType.Brush2        => 1.8
      case PenType.Marker1      | PenType.Marker2       => 1.5
      case PenType.CalligraphyPen                        => 1.4
      case PenType.TiltPencil1  | PenType.TiltPencil2   => 0.9
      case PenType.SharpPencil1 | PenType.SharpPencil2  => 0.7
      case PenType.Fineliner1   | PenType.Fineliner2     => 0.6
      case PenType.Ballpoint1   | PenType.Ballpoint2    => 1.0
      case PenType.Eraser       | PenType.EraserArea    => 4.0
      case PenType.Unknown                               => 1.0
