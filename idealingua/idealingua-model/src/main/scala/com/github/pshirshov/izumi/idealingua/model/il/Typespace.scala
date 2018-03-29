package com.github.pshirshov.izumi.idealingua.model.il

import com.github.pshirshov.izumi.idealingua.model.common.TypeId._
import com.github.pshirshov.izumi.idealingua.model.common._
import com.github.pshirshov.izumi.idealingua.model.exceptions.IDLException
import com.github.pshirshov.izumi.idealingua.model.il.ILAst.Service.DefMethod._
import com.github.pshirshov.izumi.idealingua.model.il.ILAst._
import com.github.pshirshov.izumi.idealingua.model.il.Typespace.Dependency


class Typespace(val domain: DomainDefinition) {
  protected val referenced: Map[DomainId, Typespace] = domain.referenced.mapValues(d => new Typespace(d))
  protected val types = new TypeCollection(domain)
  protected val index: Map[TypeId, ILAst] = types.index


  def apply(id: TypeId): ILAst = {
    val typeDomain = domain.id.toDomainId(id)
    if (domain.id == typeDomain) {
      id match {
        case o =>
          index(o)
      }
    } else {
      referenced(typeDomain).apply(id)
    }
  }

  protected def apply(id: InterfaceId): Interface = {
    apply(id: TypeId).asInstanceOf[Interface]
  }

  protected def apply(id: StructureId): ILStructure = {
    apply(id: TypeId).asInstanceOf[ILStructure]
  }

  protected def apply(id: DTOId): DTO = {
    apply(id: TypeId).asInstanceOf[DTO]
  }

  def apply(id: ServiceId): Service = {
    types.services(id)
  }

  def toDtoName(id: TypeId): String = types.toDtoName(id)


  def implementors(id: InterfaceId): List[InterfaceConstructors] = {
    val implementors = implementingDtos(id)
    compatibleImplementors(implementors, id)
  }

  def compatibleImplementors(id: InterfaceId): List[InterfaceConstructors] = {
    val implementors = compatibleDtos(id)
    compatibleImplementors(implementors, id)
  }

  def verify(): Unit = {
    import Typespace._
    val typeDependencies = domain.types.flatMap(extractDependencies)

    val serviceDependencies = for {
      service <- types.services.values
      method <- service.methods
    } yield {
      method match {
        case m: RPCMethod =>
          (m.signature.input ++ m.signature.output).map(i => Dependency.ServiceParameter(service.id, i))
      }
    }

    val allDependencies = typeDependencies ++ serviceDependencies.flatten


    // TODO: very ineffective!
    val missingTypes = allDependencies
      .filterNot(_.typeId.isInstanceOf[Builtin])
      .filterNot(d => types.index.contains(d.typeId))
      .filterNot(d => referenced.get(domain.id.toDomainId(d.typeId)).exists(_.types.index.contains(d.typeId)))

    if (missingTypes.nonEmpty) {
      throw new IDLException(s"Incomplete typespace: $missingTypes")
    }
  }

  def parents(id: TypeId): List[InterfaceId] = {
    id match {
      case i: InterfaceId =>
        val defn = apply(i)
        List(i) ++ defn.superclasses.interfaces.flatMap(parents)

      case i: DTOId =>
        apply(i).superclasses.interfaces.flatMap(parents)

      case _: IdentifierId =>
        List()

      case _: EnumId =>
        List()

      case _: AliasId =>
        List()

      case _: AdtId =>
        List()

      case e: Builtin =>
        throw new IDLException(s"Unexpected id: $e")
    }
  }

  def compatible(id: TypeId): List[InterfaceId] = {
    id match {
      case i: InterfaceId =>
        val defn = apply(i)
        List(i) ++ defn.superclasses.all.flatMap(compatible)

      case i: DTOId =>
        apply(i).superclasses.all.flatMap(compatible)

      case _: IdentifierId =>
        List()

      case _: EnumId =>
        List()

      case _: AliasId =>
        List()

      case _: AdtId =>
        List()

      case e: Builtin =>
        throw new IDLException(s"Unexpected id: $e")

    }
  }

  protected def implementingDtos(id: InterfaceId): List[DTOId] = {
    index.collect {
      case (tid, d: DTO) if parents(tid).contains(id) =>
        d.id
    }.toList
  }

  protected def compatibleDtos(id: InterfaceId): List[DTOId] = {
    index.collect {
      case (tid, d: DTO) if compatible(tid).contains(id) =>
        d.id
    }.toList
  }

  def structure(defn: ILStructure): Struct = {
    val parts = apply(defn.id) match {
      case i: Interface =>
        i.superclasses
      case i: DTO =>
        i.superclasses
      case _: Identifier =>
        Super.empty
    }

    Struct(defn.id, parts, extractFields(defn))
  }

  def enumFields(id: StructureId): Struct = {
    structure(apply(id))
  }

  def sameSignature(tid: StructureId): List[DTO] = {
    val sig = signature(apply(tid))

    types
      .structures
      .filterNot(_.id == tid)
      .filter(another => sig == signature(another))
      .filterNot(_.id == tid)
      .distinct
      .filterNot(id => parents(id.id).contains(tid))
      .collect({ case t: DTO => t })
      .toList
  }


  protected def extractFields(defn: ILAst): List[ExtendedField] = {
    val fields = defn match {
      case t: Interface =>
        val superFields = compositeFields(t.superclasses.interfaces)
        val embeddedFields = t.superclasses.concepts.flatMap(id => extractFields(apply(id)))
        val thisFields = toExtendedFields(t.fields, t.id)

        superFields.map(_.copy(definedBy = t.id)) ++ // in fact super field is defined by this
          embeddedFields ++
          thisFields

      case t: DTO =>
        val superFields = compositeFields(t.superclasses.interfaces)
        val embeddedFields = t.superclasses.concepts.flatMap(id => extractFields(apply(id)))
        val thisFields = toExtendedFields(t.fields, t.id)

        superFields ++
          embeddedFields ++
          thisFields

      case t: Adt =>
        t.alternatives.map(apply).flatMap(extractFields)

      case t: Identifier =>
        toExtendedPrimitiveFields(t.fields, t.id)

      case _: Enumeration =>
        List()

      case _: Alias =>
        List()
    }

    fields.distinct
  }

  protected def toExtendedFields(fields: Tuple, id: TypeId): List[ExtendedField] = {
    fields.map(f => ExtendedField(f, id: TypeId))
  }

  protected def toExtendedPrimitiveFields(fields: PrimitiveTuple, id: TypeId): List[ExtendedField] = {
    fields.map(f => ExtendedField(ILAst.Field(f.typeId, f.name), id: TypeId))
  }

  protected def compositeFields(composite: Composite): List[ExtendedField] = {
    composite.flatMap(i => extractFields(index(i)))
  }


  protected def signature(defn: ILStructure): List[Field] = {
    structure(defn).all.map(_.field)
  }


  protected def extractDependencies(definition: ILAst): Seq[Dependency] = {
    definition match {
      case _: Enumeration =>
        Seq.empty
      case d: Interface =>
        d.superclasses.interfaces.map(i => Dependency.Interface(d.id, i)) ++
          d.superclasses.concepts.flatMap(c => extractDependencies(apply(c))) ++
          d.fields.map(f => Dependency.Field(d.id, f.typeId, f))
      case d: DTO =>
        d.superclasses.interfaces.map(i => Dependency.Interface(d.id, i)) ++
          d.superclasses.concepts.flatMap(c => extractDependencies(apply(c))) ++
          d.fields.map(f => Dependency.Field(d.id, f.typeId, f))

      case d: Identifier =>
        d.fields.map(f => Dependency.PrimitiveField(d.id, f.typeId, f))

      case d: Adt =>
        d.alternatives.map(apply).flatMap(extractDependencies)

      case d: Alias =>
        Seq(Dependency.Alias(d.id, d.target))
    }
  }

  protected def compatibleImplementors(implementors: List[StructureId], id: InterfaceId): List[InterfaceConstructors] = {
    val struct = structure(apply(id))

    val parentInstanceFields = {
      val baseConflicts = struct.conflicts.softConflicts.keySet

      struct.all.map(_.field)
        .toSet
        .filterNot(f => baseConflicts.contains(f.name))
    }

    val compatibleIfs = compatible(id)

    implementors
      .map(t => structure(apply(t)))
      .map {
        istruct =>
          val localFields = istruct
            .local
            .toSet

          val inheritedFields = istruct.inherited
            .map(_.definedBy)
            .collect({ case i: StructureId => i })
            .filterNot(compatibleIfs.contains)
            .toSet

          val filteredParentFields = parentInstanceFields
            .filterNot(i => localFields.exists(_.field == i))

          val parentsAsParams = inheritedFields
            .collect({ case i: InterfaceId => i })
            .toList

          val mixinInstanceFields = istruct
            .inherited
            .map(_.definedBy)
            .collect({ case i: StructureId => i })
            .flatMap(mi => structure(apply(mi)).all)
            .filterNot(f => parentInstanceFields.contains(f.field))
            .filterNot(f => istruct.conflicts.softConflicts.keySet.contains(f.field.name))
            .toSet


          // TODO: pass definition instead of id
          InterfaceConstructors(
            istruct.id
            , parentsAsParams
            , filteredParentFields
            , mixinInstanceFields
            , localFields
            , struct
          )
      }
  }

}


object Typespace {

  trait Dependency {
    def typeId: TypeId
  }

  object Dependency {

    case class Field(definedIn: TypeId, typeId: TypeId, tpe: ILAst.Field) extends Dependency {
      override def toString: TypeName = s"[field $definedIn::${tpe.name} :$typeId]"
    }

    case class PrimitiveField(definedIn: TypeId, typeId: TypeId, tpe: ILAst.PrimitiveField) extends Dependency {
      override def toString: TypeName = s"[field $definedIn::${tpe.name} :$typeId]"
    }


    case class Parameter(definedIn: TypeId, typeId: TypeId) extends Dependency {
      override def toString: TypeName = s"[param $definedIn::$typeId]"
    }

    case class ServiceParameter(definedIn: ServiceId, typeId: TypeId) extends Dependency {
      override def toString: TypeName = s"[sparam $definedIn::$typeId]"
    }


    case class Interface(definedIn: TypeId, typeId: InterfaceId) extends Dependency {
      override def toString: TypeName = s"[interface $definedIn::$typeId]"
    }

    case class Alias(definedIn: TypeId, typeId: TypeId) extends Dependency {
      override def toString: TypeName = s"[alias $definedIn::$typeId]"
    }

  }

}
