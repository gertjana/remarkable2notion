package remarkable.render

import java.nio.{ByteBuffer, ByteOrder}

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

/** A single sampled point on a stroke path. */
case class RmPoint(
    x: Float,
    y: Float,
    speed: Float,
    direction: Float,   // stroke direction in radians (v5+)
    width: Float,       // width / pressure
    pressure: Float,
)

/** Pen / brush type as stored in the .rm file. */
enum PenType(val id: Int):
  case Ballpoint1     extends PenType(2)
  case Ballpoint2     extends PenType(15)
  case Marker1        extends PenType(3)
  case Marker2        extends PenType(16)
  case Fineliner1     extends PenType(4)
  case Fineliner2     extends PenType(17)
  case SharpPencil1   extends PenType(7)
  case SharpPencil2   extends PenType(13)
  case TiltPencil1    extends PenType(1)
  case TiltPencil2    extends PenType(14)
  case Brush1         extends PenType(0)
  case Brush2         extends PenType(12)
  case Highlighter1   extends PenType(5)
  case Highlighter2   extends PenType(18)
  case Eraser         extends PenType(6)
  case EraserArea     extends PenType(8)
  case CalligraphyPen extends PenType(21)
  case Unknown        extends PenType(-1)

object PenType:
  def fromId(id: Int): PenType =
    values.find(_.id == id).getOrElse(Unknown)

/** Color as stored in the .rm file. */
enum StrokeColor(val id: Int):
  case Black   extends StrokeColor(0)
  case Grey    extends StrokeColor(1)
  case White   extends StrokeColor(2)
  case Yellow  extends StrokeColor(3)   // highlight
  case Green   extends StrokeColor(4)   // highlight
  case Pink    extends StrokeColor(5)   // highlight
  case Blue    extends StrokeColor(6)
  case Red     extends StrokeColor(7)
  case Unknown extends StrokeColor(-1)

object StrokeColor:
  def fromId(id: Int): StrokeColor =
    values.find(_.id == id).getOrElse(Unknown)

case class RmStroke(
    pen: PenType,
    color: StrokeColor,
    brushSize: Double,
    points: Vector[RmPoint],
)

case class RmLayer(strokes: Vector[RmStroke])

/** A parsed .rm page.
  *
  * @param layers    Stroke layers extracted from the file.
  * @param canvasWidth  Native canvas width in coordinate units (from SceneInfo, or 0 if unknown).
  * @param canvasHeight Native canvas height in coordinate units (from SceneInfo, or 0 if unknown).
  */
case class RmPage(
    layers: Vector[RmLayer],
    canvasWidth: Int  = 0,
    canvasHeight: Int = 0,
)

// ---------------------------------------------------------------------------
// Parser result / error
// ---------------------------------------------------------------------------

case class RmParseError(message: String) extends RuntimeException(message)

// ---------------------------------------------------------------------------
// Parser
// ---------------------------------------------------------------------------

/** Parses the binary reMarkable `.rm` stroke format.
  *
  * Supports format versions 3, 5 (flat struct layout) and 6 (CRDT tagged-block
  * stream as used on reMarkable firmware >= 3.0).
  *
  * V3/V5 file layout:
  *   - 43-byte ASCII header  "reMarkable .lines file, version=X          "
  *   - 4-byte padding / unused
  *   - 4-byte int: number of layers
  *   for each layer:
  *     - 4-byte int: number of strokes
  *     for each stroke: pen, color, double, brushSize, [v5 float], numPoints,
  *       then numPoints × {x, y, speed, direction, width, pressure} floats
  *
  * V6 file layout (CRDT tagged-block stream, rmscene format):
  *   - 43-byte ASCII header  "reMarkable .lines file, version=6          "
  *   - Stream of tagged blocks, each: u32 len, u8 unknown, u8 minVer, u8 curVer, u8 blockType
  *   - Only SceneLineItemBlock (type=0x05) is consumed; all others are skipped.
  *   - All strokes extracted from SceneLineItemBlocks are placed in one layer.
  */
object RmParser:

  // All versions share the same 43-byte header prefix (with differing version digit)
  private val MagicPrefix = "reMarkable .lines file, version="

  // The header is exactly 43 bytes (including trailing spaces).
  private val HeaderLength = 43

  def parse(bytes: Array[Byte]): RmPage =
    try doParse(bytes)
    catch
      case e: RmParseError => throw e
      case e: java.nio.BufferUnderflowException =>
        throw RmParseError(s"Unexpected end of .rm data (file truncated or corrupt): ${e.getMessage}")
      case e: Exception =>
        throw RmParseError(s"Unexpected error parsing .rm data: ${e.getMessage}")

  private def doParse(bytes: Array[Byte]): RmPage =
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    // --- Header ---
    val headerBytes = new Array[Byte](HeaderLength)
    buf.get(headerBytes)
    val header = new String(headerBytes, "ASCII").trim

    if !header.startsWith(MagicPrefix) then
      throw RmParseError(s"Unrecognised .rm header: '${header.take(50)}'")

    val versionStr = header.stripPrefix(MagicPrefix).filter(_.isDigit)
    val version = versionStr.toIntOption.getOrElse(
      throw RmParseError(s"Cannot parse version from header: '$header'")
    )

    version match
      case 3 | 5 => parseV3V5(buf, version)
      case 6     => parseV6(buf)
      case other => throw RmParseError(s"Unsupported .rm format version: $other")

  // ===========================================================================
  // V3 / V5 flat struct parser
  // ===========================================================================

  private def parseV3V5(buf: ByteBuffer, version: Int): RmPage =
    // 4 padding bytes after header (unused)
    buf.position(buf.position() + 4)

    val numLayers = buf.getInt
    val layers = Vector.tabulate(numLayers) { _ =>
      parseLayerV3V5(buf, version)
    }
    RmPage(layers)

  private def parseLayerV3V5(buf: ByteBuffer, version: Int): RmLayer =
    val numStrokes = buf.getInt
    val strokes = Vector.tabulate(numStrokes) { _ =>
      parseStrokeV3V5(buf, version)
    }
    RmLayer(strokes)

  private def parseStrokeV3V5(buf: ByteBuffer, version: Int): RmStroke =
    val penId    = buf.getInt
    val colorId  = buf.getInt
    buf.getDouble                      // unknown double, present in all versions
    val brushSize = buf.getFloat
    if version >= 5 then buf.getFloat  // extra float added in v5

    val pen   = PenType.fromId(penId)
    val color = StrokeColor.fromId(colorId)

    val numPoints = buf.getInt
    val points = Vector.tabulate(numPoints) { _ =>
      val x         = buf.getFloat
      val y         = buf.getFloat
      val speed     = buf.getFloat
      val direction = buf.getFloat
      val width     = buf.getFloat
      val pressure  = buf.getFloat
      RmPoint(x, y, speed, direction, width, pressure)
    }

    RmStroke(pen, color, brushSize.toDouble, points)

  // ===========================================================================
  // V6 CRDT tagged-block stream parser
  //
  // Reference: https://github.com/ricklupton/rmscene
  //   tagged_block_common.py  — DataStream, TagType, CrdtId, varuint
  //   tagged_block_reader.py  — TaggedBlockReader, block/subblock framing
  //   scene_stream.py         — SceneLineItemBlock (type 0x05), line_from_stream
  // ===========================================================================

  // Block type IDs
  private val BlockTypeSceneLineItem = 0x05
  private val BlockTypeSceneInfo     = 0x0D

  // Tag type constants
  private val TagTypeID      = 0xF
  private val TagTypeLength4 = 0xC
  private val TagTypeByte8   = 0x8
  private val TagTypeByte4   = 0x4
  private val TagTypeByte1   = 0x1

  private def parseV6(buf: ByteBuffer): RmPage =
    val strokes = scala.collection.mutable.ArrayBuffer[RmStroke]()
    var canvasWidth  = 0
    var canvasHeight = 0

    while buf.remaining() >= 8 do
      // Block header: u32 blockLen, u8 unknown, u8 minVersion, u8 curVersion, u8 blockType
      val blockLen     = buf.getInt & 0xFFFFFFFFL   // unsigned
      val _unknown     = buf.get & 0xFF
      val _minVersion  = buf.get & 0xFF
      val curVersion   = buf.get & 0xFF
      val blockType    = buf.get & 0xFF

      val blockStart = buf.position().toLong
      val blockEnd   = blockStart + blockLen        // Long — no overflow

      if blockType == BlockTypeSceneLineItem then
        try
          val stroke = parseSceneLineItemBlock(buf, curVersion, blockEnd)
          stroke.foreach(strokes += _)
        catch
          case e: Exception =>
            val msg = if e.getMessage != null then e.getMessage else e.getClass.getSimpleName
            System.err.println(s"[WARN] Error parsing SceneLineItemBlock: $msg")

      else if blockType == BlockTypeSceneInfo then
        try
          val (w, h) = parseSceneInfoBlock(buf, blockEnd)
          if w > 0 then canvasWidth  = w
          if h > 0 then canvasHeight = h
        catch
          case e: Exception => () // non-fatal; fall back to defaults

      // Always advance to end of block
      if buf.position().toLong < blockEnd then
        if blockEnd <= buf.limit().toLong then
          buf.position(blockEnd.toInt)
        else
          buf.position(buf.limit())

    val layer = RmLayer(strokes.toVector)
    RmPage(Vector(layer), canvasWidth, canvasHeight)

  // ---------------------------------------------------------------------------
  // SceneLineItemBlock (type 0x05) — contains one stroke
  // ---------------------------------------------------------------------------

  /** Returns Some(stroke) if the block contains a valid stroke, None otherwise
    * (e.g. deletion / empty item).
    *
    * Block layout (from rmscene SceneItemBlock.from_stream):
    *   tag 1 (ID)      = parent_id  (CrdtId)
    *   tag 2 (ID)      = item_id    (CrdtId)
    *   tag 3 (ID)      = left_id    (CrdtId)
    *   tag 4 (ID)      = right_id   (CrdtId)
    *   tag 5 (Byte4)   = deleted_length (u32)
    *   tag 6 (Length4) = value subblock [optional, absent for tombstones]
    *     subblock starts with: u8 item_type (0x03 for Line)
    *     then: line fields (tagged: tool@1, color@2, thickness@3, startLen@4, points@5, ts@6, moveId@7)
    */
  private def parseSceneLineItemBlock(
      buf: ByteBuffer,
      blockVersion: Int,
      blockEnd: Long,
  ): Option[RmStroke] =

    var penId: Int        = -1
    var colorId: Int      = -1
    var thickness: Double = 2.0
    var points: Vector[RmPoint] = Vector.empty
    var hasLine = false

    while buf.position().toLong < blockEnd do
      val tagVal  = readVarUInt(buf).toInt
      val index   = (tagVal >> 4) & 0xFF
      val tagType = tagVal & 0xF

      index match
        case 1 => skipCrdtId(buf)   // parent_id
        case 2 => skipCrdtId(buf)   // item_id
        case 3 => skipCrdtId(buf)   // left_id
        case 4 => skipCrdtId(buf)   // right_id
        case 5 => buf.getInt        // deleted_length (u32) — discard

        case 6 => // value subblock — the actual Line data (optional)
          val subLen   = buf.getInt & 0xFFFFFFFFL
          val subStart = buf.position().toLong
          val subEnd   = subStart + subLen

          // First byte inside subblock: item_type (must be 0x03 for Line)
          val itemType = buf.get & 0xFF
          if itemType == 0x03 then
            hasLine = true
            readLineFields(buf, blockVersion, subEnd) match
              case Some((p, c, t, pts)) =>
                penId     = p
                colorId   = c
                thickness = t
                points    = pts
              case None => ()

          if buf.position().toLong < subEnd then buf.position(subEnd.toInt)

        case _ => // unknown tag — skip based on tag type
          skipTagValue(buf, tagType)

    if hasLine && points.nonEmpty then
      val pen   = PenType.fromId(penId)
      val color = StrokeColor.fromId(colorId)
      Some(RmStroke(pen, color, thickness, points))
    else
      None

  // ---------------------------------------------------------------------------
  // Line fields reader (the subblock inside SceneLineItemBlock index=3)
  // ---------------------------------------------------------------------------

  /** Reads the tagged fields of a Line value from the subblock.
    * Returns (penId, colorId, thicknessScale, points) or None if not a line.
    *
    * Field tags (from rmscene scene_stream.py line_from_stream):
    *   tag 1: tool/pen id (u32)
    *   tag 2: color id (u32)
    *   tag 3: thickness_scale (f64)
    *   tag 4: starting_length (f32)
    *   tag 5: points subblock (raw point data)
    *   tag 6: timestamp CrdtId (skip)
    *   tag 7: move_id CrdtId (skip, optional)
    */
  private def readLineFields(
      buf: ByteBuffer,
      blockVersion: Int,
      subEnd: Long,
  ): Option[(Int, Int, Double, Vector[RmPoint])] =
    var penId: Int        = -1
    var colorId: Int      = -1
    var thickness: Double = 2.0
    var points: Vector[RmPoint] = Vector.empty

    while buf.position().toLong < subEnd do
      val tagVal  = readVarUInt(buf).toInt
      val index   = (tagVal >> 4) & 0xFF
      val tagType = tagVal & 0xF

      index match
        case 1 => // tool id (u32, TagType Byte4)
          penId = buf.getInt

        case 2 => // color id (u32, TagType Byte4)
          colorId = buf.getInt

        case 3 => // thickness_scale (f64, TagType Byte8)
          thickness = buf.getDouble

        case 4 => // starting_length (f32, TagType Byte4)
          buf.getFloat // skip

        case 5 => // points subblock (TagType Length4)
          val ptSubLen = buf.getInt & 0xFFFFFFFFL
          val ptSubEnd = buf.position().toLong + ptSubLen
          points = readPoints(buf, blockVersion, ptSubEnd)
          if buf.position().toLong < ptSubEnd then buf.position(ptSubEnd.toInt)

        case 6 => // timestamp CrdtId
          skipCrdtId(buf)

        case 7 => // move_id CrdtId (optional)
          skipCrdtId(buf)

        case _ => // unknown
          skipTagValue(buf, tagType)

    Some((penId, colorId, thickness, points))

  // ---------------------------------------------------------------------------
  // Point data reader
  // ---------------------------------------------------------------------------

  /** Reads raw point data from a points subblock.
    *
    * Point encoding depends on block version (from rmscene point_from_stream):
    *   version >= 2: x:f32, y:f32, speed:u16, width:u16, direction:u8, pressure:u8  (14 bytes)
    *   version == 1: x:f32, y:f32, speed:f32, direction:f32, width:f32, pressure:f32 (24 bytes)
    */
  private def readPoints(buf: ByteBuffer, blockVersion: Int, subEnd: Long): Vector[RmPoint] =
    val pointSize = if blockVersion >= 2 then 14 else 24
    val numPoints = ((subEnd - buf.position().toLong) / pointSize).max(0).toInt
    Vector.tabulate(numPoints.max(0)) { _ =>
      if blockVersion >= 2 then
        val x         = buf.getFloat
        val y         = buf.getFloat
        val speed     = (buf.getShort & 0xFFFF).toFloat / 4.0f  // u16, scale factor per rmscene
        val width     = (buf.getShort & 0xFFFF).toFloat / 4.0f  // u16
        val direction = (buf.get & 0xFF).toFloat / 255.0f * (2 * Math.PI.toFloat)  // u8 → radians
        val pressure  = (buf.get & 0xFF).toFloat / 255.0f        // u8 → 0..1
        RmPoint(x, y, speed, direction, width, pressure)
      else
        // version 1: all f32
        val x         = buf.getFloat
        val y         = buf.getFloat
        val speed     = buf.getFloat
        val direction = buf.getFloat
        val width     = buf.getFloat
        val pressure  = buf.getFloat
        RmPoint(x, y, speed, direction, width, pressure)
    }

  // ---------------------------------------------------------------------------
  // Low-level helpers
  // ---------------------------------------------------------------------------

  /** Reads a varuint (LEB128 unsigned) from the buffer. */
  private def readVarUInt(buf: ByteBuffer): Long =
    var result = 0L
    var shift  = 0
    var byte   = 0
    while {
      byte = buf.get & 0xFF
      result |= (byte & 0x7FL) << shift
      shift += 7
      (byte & 0x80) != 0
    } do ()
    result

  /** Reads a CrdtId: u8 part1 + varuint part2. */
  private def skipCrdtId(buf: ByteBuffer): Unit =
    buf.get()          // part1: u8
    readVarUInt(buf)   // part2: varuint (discard)

  /** Skips a tag value of the given tag type (called when the tag index is unknown). */
  private def skipTagValue(buf: ByteBuffer, tagType: Int): Unit =
    tagType match
      case t if t == TagTypeID      => skipCrdtId(buf)
      case t if t == TagTypeLength4 => val len = buf.getInt & 0xFFFFFFFFL; buf.position((buf.position().toLong + len).toInt)
      case t if t == TagTypeByte8   => buf.position(buf.position() + 8)
      case t if t == TagTypeByte4   => buf.position(buf.position() + 4)
      case t if t == TagTypeByte1   => buf.position(buf.position() + 1)
      case _                        => throw RmParseError(s"Unknown tag type 0x${tagType.toHexString} — cannot skip")

  // ---------------------------------------------------------------------------
  // SceneInfo block (type 0x0D) — canvas dimensions
  // ---------------------------------------------------------------------------

  /** Reads a SceneInfo block and returns (width, height) if paper_size tag (index=5) is present.
    *
    * SceneInfo layout (from rmscene scene_stream.py):
    *   tag 1: current_layer (LWW ID)       — skip
    *   tag 2: background_visible (LWW bool) — skip
    *   tag 3: root_document_visible (LWW bool) — skip
    *   tag 5: paper_size (pair of u32)     — width, height
    */
  private def parseSceneInfoBlock(buf: ByteBuffer, blockEnd: Long): (Int, Int) =
    var width  = 0
    var height = 0

    while buf.position().toLong < blockEnd do
      val tagVal  = readVarUInt(buf).toInt
      val index   = (tagVal >> 4) & 0xFF
      val tagType = tagVal & 0xF

      index match
        case 5 => // paper_size: Length4 subblock containing two u32 values
          val subLen = buf.getInt & 0xFFFFFFFFL
          val subEnd = buf.position().toLong + subLen
          if subLen >= 8 then
            width  = buf.getInt
            height = buf.getInt
          if buf.position().toLong < subEnd then buf.position(subEnd.toInt)

        case _ =>
          skipTagValue(buf, tagType)

    (width, height)
