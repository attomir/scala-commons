package com.avsystem.commons
package serialization


import java.io.{DataInputStream, DataOutputStream}
import java.{lang => jl}

import com.avsystem.commons.misc.Opt
import com.avsystem.commons.serialization.GenCodec.ReadFailure


private object FormatConstants {
  val ByteBytes = jl.Byte.BYTES
  val ShortBytes = jl.Short.BYTES
  val IntBytes = jl.Integer.BYTES
  val LongBytes = jl.Long.BYTES
  val FloatBytes = jl.Float.BYTES
  val DoubleBytes = jl.Double.BYTES

  val NullMarker: Byte = 0
  val StringMarker: Byte = 1
  val ByteMarker: Byte = 2
  val ShortMarker: Byte = 3
  val IntMarker: Byte = 4
  val LongMarker: Byte = 5
  val FloatMarker: Byte = 6
  val DoubleMarker: Byte = 7
  val ByteArrayMarker: Byte = 8
  val BooleanMarker: Byte = 9
  val ListStartMarker: Byte = 10
  val ObjectStartMarker: Byte = 11
  val ListEndMarker: Byte = 12
  val ObjectEndMarker: Byte = 13
}

import com.avsystem.commons.serialization.FormatConstants._

class StreamInput(is: DataInputStream) extends Input {
  private[serialization] val markerByte = is.readByte()

  def readNull(): ValueRead[Null] = if (markerByte == NullMarker)
    ReadSuccessful(null)
  else
    ReadFailed(s"Expected null, but $markerByte found")

  def readString(): ValueRead[String] = if (markerByte == StringMarker)
    ReadSuccessful(is.readUTF())
  else
    ReadFailed(s"Expected string, but $markerByte found")

  def readBoolean(): ValueRead[Boolean] = if (markerByte == BooleanMarker)
    ReadSuccessful(is.readBoolean())
  else
    ReadFailed(s"Expected boolean, but $markerByte found")

  def readInt(): ValueRead[Int] = if (markerByte == IntMarker)
    ReadSuccessful(is.readInt())
  else
    ReadFailed(s"Expected int, but $markerByte found")

  def readLong(): ValueRead[Long] = if (markerByte == LongMarker)
    ReadSuccessful(is.readLong())
  else
    ReadFailed(s"Expected long, but $markerByte found")

  def readDouble(): ValueRead[Double] = if (markerByte == DoubleMarker)
    ReadSuccessful(is.readDouble())
  else
    ReadFailed(s"Expected double, but $markerByte found")

  def readBinary(): ValueRead[Array[Byte]] = if (markerByte == ByteArrayMarker)
    ReadSuccessful {
      val binary = Array.ofDim[Byte](is.readInt())
      is.readFully(binary)
      binary
    }
  else
    ReadFailed(s"Expected binary array, but $markerByte found")

  def readList(): ValueRead[ListInput] = if (markerByte == ListStartMarker)
    ReadSuccessful(new StreamListInput(is))
  else
    ReadFailed(s"Expected list, but $markerByte found")

  def readObject(): ValueRead[ObjectInput] = if (markerByte == ObjectStartMarker)
    ReadSuccessful(new StreamObjectInput(is))
  else
    ReadFailed(s"Expected object, but $markerByte found")

  def skip(): Unit = {
    val toSkip = markerByte match {
      case NullMarker =>
      // no op
      case StringMarker =>
        val n = is.readUnsignedShort()
        is.skipBytes(n)
      case ByteMarker =>
        is.skipBytes(ByteBytes)
      case ShortMarker =>
        is.skipBytes(ShortBytes)
      case IntMarker =>
        is.skipBytes(IntBytes)
      case LongMarker =>
        is.skipBytes(LongBytes)
      case FloatMarker =>
        is.skipBytes(FloatBytes)
      case DoubleMarker =>
        is.skipBytes(DoubleBytes)
      case ByteArrayMarker =>
        is.skipBytes(is.readInt())
      case BooleanMarker =>
        is.skipBytes(ByteBytes)
      case ListStartMarker =>
        new StreamListInput(is).skipRemaining()
      case ObjectStartMarker =>
        new StreamObjectInput(is).skipRemaining()
      case unexpected =>
        throw new ReadFailure(s"Unexpected marker byte: $unexpected")
    }
  }
}

private class StreamListInput(is: DataInputStream) extends ListInput {
  private[this] var currentInput: Opt[StreamInput] = Opt.empty

  private def ensureInput(): Unit =
    if (currentInput == Opt.empty) currentInput = Opt.some(new StreamInput(is))

  def nextElement(): Input = {
    if (!hasNext) throw new ReadFailure("List already emptied")
    val input = currentInput
    currentInput = Opt.empty
    input.get
  }

  def hasNext: Boolean = {
    ensureInput()
    currentInput.get.markerByte != ListEndMarker
  }
}

private class StreamObjectInput(is: DataInputStream) extends ObjectInput {

  import StreamObjectInput._

  private[this] var currentField: (String, Input) = NoneYet

  private def ensureInput(): Unit = {
    if (currentField eq NoneYet) {
      val keyInput = new StreamInput(is)
      currentField = if (keyInput.markerByte != ObjectEndMarker) {
        val keyString = keyInput.readString().get
        val valueInput = new StreamInput(is)
        (keyString, valueInput)
      } else {
        End
      }
    }
  }

  def nextField(): (String, Input) = {
    if (!hasNext) throw new ReadFailure("Object already emptied")
    val field = currentField
    currentField = NoneYet
    field
  }

  def hasNext: Boolean = {
    ensureInput()
    currentField ne End
  }
}

private object StreamObjectInput {
  val NoneYet = ("NONE", null)
  val End = ("END", null)
}

class StreamOutput(os: DataOutputStream) extends Output {

  private[this] val streamList = new StreamListOutput(os, this)
  private[this] val streamObject = new StreamObjectOutput(os, this)

  def writeNull(): Unit = os.write(NullMarker)

  def writeString(str: String): Unit = {
    os.writeByte(StringMarker)
    os.writeUTF(str)
  }

  def writeBoolean(boolean: Boolean): Unit = {
    os.writeByte(BooleanMarker)
    os.writeBoolean(boolean)
  }

  def writeInt(int: Int): Unit = {
    os.writeByte(IntMarker)
    os.writeInt(int)
  }

  def writeLong(long: Long): Unit = {
    os.writeByte(LongMarker)
    os.writeLong(long)
  }

  def writeDouble(double: Double): Unit = {
    os.writeByte(DoubleMarker)
    os.writeDouble(double)
  }

  def writeBinary(binary: Array[Byte]): Unit = {
    os.writeByte(ByteArrayMarker)
    os.writeInt(binary.length)
    os.write(binary)
  }

  def writeList(): ListOutput = {
    os.writeByte(ListStartMarker)
    streamList
  }

  def writeObject(): ObjectOutput = {
    os.writeByte(ObjectStartMarker)
    streamObject
  }
}

private class StreamListOutput(os: DataOutputStream, output: StreamOutput) extends ListOutput {

  def writeElement(): Output = output

  def finish(): Unit = {
    os.writeByte(ListEndMarker)
  }
}

private class StreamObjectOutput(os: DataOutputStream, output: StreamOutput) extends ObjectOutput {

  def writeField(key: String): Output = {
    output.writeString(key)
    output
  }

  def finish(): Unit = {
    os.writeByte(ObjectEndMarker)
  }
}
