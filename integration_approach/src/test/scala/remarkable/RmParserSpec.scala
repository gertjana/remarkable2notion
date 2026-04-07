package remarkable

import remarkable.render.{PenType, RmPage, RmParser, RmParseError, StrokeColor}
import zio.test.*
import zio.test.Assertion.*

import java.nio.{ByteBuffer, ByteOrder}

object RmParserSpec extends ZIOSpecDefault:

  // ---------------------------------------------------------------------------
  // Helpers to build minimal valid .rm byte arrays
  // ---------------------------------------------------------------------------

  /** Builds a well-formed v5 .rm file with the given layers/strokes/points. */
  private def buildRmV5(
      layers: List[List[(Int, Int, Double, Float, Int, List[(Float, Float, Float, Float, Float, Float)])]]
  ): Array[Byte] =
    val buf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)

    // 43-byte header (padded with spaces to fill)
    val magic = "reMarkable .lines file, version=5          "
    buf.put(magic.take(43).getBytes("ASCII"))

    // 4 padding bytes
    buf.putInt(0)

    // Number of layers
    buf.putInt(layers.size)

    for layer <- layers do
      buf.putInt(layer.size)   // number of strokes
      for (penId, colorId, unknown, brushSize, numPts, pts) <- layer do
        buf.putInt(penId)
        buf.putInt(colorId)
        buf.putDouble(unknown)
        buf.putFloat(brushSize)
        buf.putFloat(0f)       // extra v5 float
        buf.putInt(numPts)
        for (x, y, speed, dir, width, pressure) <- pts do
          buf.putFloat(x)
          buf.putFloat(y)
          buf.putFloat(speed)
          buf.putFloat(dir)
          buf.putFloat(width)
          buf.putFloat(pressure)

    val size = buf.position()
    val result = new Array[Byte](size)
    buf.rewind()
    buf.get(result)
    result

  // Shorthand for a single stroke with two points
  private def oneStrokeLayer(penId: Int = 2, colorId: Int = 0): List[(Int, Int, Double, Float, Int, List[(Float, Float, Float, Float, Float, Float)])] =
    List((penId, colorId, 0.0, 2.0f, 2,
      List(
        (100f, 200f, 50f, 0f, 1.5f, 0.8f),
        (150f, 250f, 60f, 0f, 1.5f, 0.9f),
      )
    ))

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  def spec = suite("RmParser")(

    test("parses a v5 file with one layer, one stroke, two points") {
      val bytes = buildRmV5(List(oneStrokeLayer()))
      val page  = RmParser.parse(bytes)

      assertTrue(page.layers.size == 1) &&
      assertTrue(page.layers.head.strokes.size == 1) &&
      assertTrue(page.layers.head.strokes.head.points.size == 2) &&
      assertTrue(page.layers.head.strokes.head.pen == PenType.Ballpoint1) &&
      assertTrue(page.layers.head.strokes.head.color == StrokeColor.Black)
    },

    test("parses a v5 file with multiple layers") {
      val bytes = buildRmV5(List(oneStrokeLayer(), oneStrokeLayer(), oneStrokeLayer()))
      val page  = RmParser.parse(bytes)
      assertTrue(page.layers.size == 3)
    },

    test("parses a v5 file with zero layers (blank page)") {
      val bytes = buildRmV5(List.empty)
      val page  = RmParser.parse(bytes)
      assertTrue(page.layers.isEmpty)
    },

    test("recognises pen types correctly") {
      val bytes = buildRmV5(List(oneStrokeLayer(penId = 5)))   // Highlighter1
      val page  = RmParser.parse(bytes)
      assertTrue(page.layers.head.strokes.head.pen == PenType.Highlighter1)
    },

    test("recognises stroke colors correctly") {
      val bytes = buildRmV5(List(oneStrokeLayer(colorId = 1)))  // Grey
      val page  = RmParser.parse(bytes)
      assertTrue(page.layers.head.strokes.head.color == StrokeColor.Grey)
    },

    test("maps unknown pen id to PenType.Unknown") {
      val bytes = buildRmV5(List(oneStrokeLayer(penId = 999)))
      val page  = RmParser.parse(bytes)
      assertTrue(page.layers.head.strokes.head.pen == PenType.Unknown)
    },

    test("maps unknown color id to StrokeColor.Unknown") {
      val bytes = buildRmV5(List(oneStrokeLayer(colorId = 999)))
      val page  = RmParser.parse(bytes)
      assertTrue(page.layers.head.strokes.head.color == StrokeColor.Unknown)
    },

    test("throws RmParseError on invalid header") {
      val badBytes = "this is not a rm file".getBytes("ASCII")
      val result   = scala.util.Try(RmParser.parse(badBytes))
      assertTrue(result.isFailure) &&
      assertTrue(result.failed.get.isInstanceOf[RmParseError])
    },

    test("preserves point coordinates exactly") {
      val bytes = buildRmV5(List(List(
        (2, 0, 0.0, 1.5f, 1, List((123.45f, 678.9f, 10f, 0f, 2f, 0.5f)))
      )))
      val page  = RmParser.parse(bytes)
      val pt    = page.layers.head.strokes.head.points.head

      assertTrue(math.abs(pt.x - 123.45f) < 0.001f) &&
      assertTrue(math.abs(pt.y - 678.9f)  < 0.001f) &&
      assertTrue(math.abs(pt.pressure - 0.5f) < 0.001f)
    },
  )
