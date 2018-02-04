package com.github.pshirshov.izumi.idealingua.model.runtime


import scala.reflect._

trait IDLGenerated {

}

trait IDLIdentifier {
  this: IDLGenerated =>
}

object IDLIdentifier {
  // TODO: here we should escape colons
  def escape(s: String): String = s

  def unescape(s: String): String = s
}

trait IDLService {
  type InputType <: IDLInput
  def inputTag: ClassTag[InputType]
}

trait IDLRpc {}


trait IDLInput extends IDLRpc {

}

trait IDLOutput extends IDLRpc {

}

