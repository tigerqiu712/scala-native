# Native Intermediate Representation (NIR)

NIR is high-level object-oriented SSA-based representation. The core of the
representation is a subset of LLVM instructions, types and values, augmented
with a number of high-level primitives that are necessary to
efficiently compiler modern languages like Scala.

## Overview

Lets have a look at the textual form of NIR generated for a simple Scala module:

```scala
package test

import System.out.println

object Test {
  def main(args: Array[String]): Unit =
    println("Hello, world!")
}
```

Would map to:

```
pin(@test.Test::init) module @test.Test : #java.lang.Object

def @test.Test::main_class.ssnr.ObjectArray_unit : (module @test.Test, class #scala.scalanative.runtime.ObjectArray) => unit {
  %src.2(%src.0: module @test.Test, %src.1: class #scala.scalanative.runtime.ObjectArray):
    %src.3 = module @java.lang.System
    %src.4 = field[...] %src.3: module @java.lang.System, @java.lang.System::field.out
    %src.5 = load[...] %src.4: ptr
    %src.6 = method[...] %src.5: class #java.io.PrintStream, #java.io.PrintStream::println_class.java.lang.String_unit
    %src.7 = call[...] %src.6: ptr(%src.5: class #java.io.PrintStream, "Hello, world!")
    ret %src.7: unit
}

def @test.Test::init : (module @test.Test) => void {
  %src.1(%src.0: module @test.Test):
    %src.2 = call[(class #java.lang.Object) => void] #java.lang.Object::init(%src.0: module @test.Test)
    ret unit
}
```

Here we can see a few major points:

1. At its core NIR is very much a classical SSA-based representation.
   The code consists of basic blocks of instructions. Instructions take
   value and type parameters. Control flow instructions can only appear
   as the last instruction of the basic block.

2. Basic blocks have parameters. Parameters directly correspond to phi
   instructions in the classical SSA.

3. The representation is strongly typed. All parameters have corresponding type
   annotations. Instructions may take type arguments (they are ommited
   here for brevity.)

4. Unlike LLVM, it has support for high-level features such as java-like
   classes. Classes may contain methods and fields. There is no overloading
   or access control modifiers so names must be mangled appropriately.

5. All definitions live in a single top-level scope. During compilation they
   are lazily loaded until all reachable definitions have been discovered.
   `pin` and `pin-if` attributes are used to expressed additional dependencies.
   Nesting/ownership is of definitions is expressed through names.

## Definitions

Low-level definitions:

1. **Var**: `..$attrs var @$name: $type = $value`

   Corresponds to LLVM's [`global`](http://llvm.org/docs/LangRef.html#global-variables)
   when used in the top-level scope and to fields, when used as a member of
   classes and modules.

1. **Const**: `..$attrs var @$name: $type = $value`

   Corresponds to LLVM's [`const`](http://llvm.org/docs/LangRef.html#global-variables)
   when used in the top-level scope.

1. **Function declaration**: `..$attrs def @$name: $type`

   Correspond to LLVM's
   [`declare`](http://llvm.org/docs/LangRef.html#functions)
   when used on the top-level of the compilation unit and
   to abstract methods when used inside classes and traits.

1. **Function definition**: `..$attrs def @$name: $type { ..$blocks }`

   Corresponds to LLVM's
   [`define`](http://llvm.org/docs/LangRef.html#functions)
   when used on the top-level of the compilation unit and
   to normal methods when used inside classes, traits and modules.

1. **Struct**: `..$attrs struct @$name { ..$types }`

   Corresponds to LLVM's
   [`%$name = type { ... }`](http://llvm.org/docs/LangRef.html#structure-types)
   struct definition.

High-level definitions:

1. **Trait**: `..$attrs trait #$name : ..$interfaces`

   Scala-like classes. May contain abstract and concrete methods as members.

1. **Class**: `..$attrs class #$name : $parent, ..$traits`

   Scala-like classes. May contain vars, abstract and concrete methods as members.

1. **Module**: `..$attrs module @$name : $parent, ..$interfaces`

   Scala-like modules (i.e. `object $name`) May contains vars and concrete
   methods as members.

## Types

Low-level types:

1. **Void**: `void`

   Corresponds to LLVM's [`void`](http://llvm.org/docs/LangRef.html#void-type).

1. **Boolean**: `bool`

   Corresponds to LLVM's [`i1`](http://llvm.org/docs/LangRef.html#integer-type) and
   C's [`bool`](http://pubs.opengroup.org/onlinepubs/009695399/basedefs/stdbool.h.html).

1. **Integer**: `i8`, `i16`, `i32`, `i64`

   Corresponds to LLVM [integer type](http://llvm.org/docs/LangRef.html#integer-type).

1. **Floating point**: `f32`, `f64`

   Corresponds to LLVM's [floating point types](http://llvm.org/docs/LangRef.html#floating-point-types).

1. **Array**: `[$type x N]`

   Corresponds to LLVM's [aggregate array type](http://llvm.org/docs/LangRef.html#array-type).

1. **Pointer**: `ptr`

   Corresponds to LLVM's [pointer type](http://llvm.org/docs/LangRef.html#pointer-type)
   with a major distinction of not preserving the type of memory that's being
   pointed at. Pointers are going to become untyped in LLVM in near future too,
   currently we just always compile them to `i8*`.

1. **Function**: `(..$args) => $ret`

   Corresponds to LLVM's [function type](http://llvm.org/docs/LangRef.html#function-type).

1. **Struct**: `struct #$name`

   Corresponds to LLVM's [aggregate structure type](http://llvm.org/docs/LangRef.html#structure-type).

High-level types:

1. **Unit**: `unit`

   Corresponds to `scala.Unit`

1. **Nothing**: `nothing`

   Corresponds to `scala.Nothing`

1. **Class**: `class #$name`

   A reference to class instance.

1. **Interface**: `trait #$name`

   A reference to interface instance.

1. **Module**: `module @$name`

   A reference to scala-style module.

## Basic Blocks & Control-Flow

Low-level control-flow instructions:

1. **Unreachable**: `unreachable`

   If execution reaches undefined instruction the behaviour of execution is undefined
   starting from that point. Corresponds to LLVM's
   [`unreachable`](http://llvm.org/docs/LangRef.html#unreachable-instruction).

1. **Return**: `ret $value`

   Returns a value. Corresponds to LLVM's
   [`ret`](http://llvm.org/docs/LangRef.html#ret-instruction).

1. **Unconditional jump**: `jump $next(..$values)`

   Jumps to the next basic block with provided values for the parameters.
   Corresponds to LLVM's unconditional version of
   [`br`](http://llvm.org/docs/LangRef.html#br-instruction).

1. **Conditional jump**: `if $cond then $next1(..$values1) else $next2(..$values2)`

   Conditionally jumps to one of the basic blocks.
   Corresponds to LLVM's conditional form of
   [`br`](http://llvm.org/docs/LangRef.html#br-instruction).

1. **Switch**:
   ```
   switch $value {
      case $value1 => $next1(..$values1)
      ...
      default      => $nextN(..$valuesN)
   }
   ```
   Jumps to one of the basic blocks if `$value` matches corresponding `$valueN`.
   Corresponds to LLVM's
   [`switch`](http://llvm.org/docs/LangRef.html#switch-instruction).

1. **Invoke**: `invoke[$type] $funptr(..$values) to $success unwind $failure`

   Invoke function pointer, jump to success in case value is returned,
   unwind to failure if exception was thrown. Corresponds to LLVM's
   [`invoke`](http://llvm.org/docs/LangRef.html#invoke-instruction).

1. **Resume**: `resume $excrec`.

High-level control flow instructions:

1. **Throw**:`throw $value`

   Throws the values and starts unwinding.

1. **Try**: `try $succ catch $failure`

## Operations

All non-control-flow instructions follow general pattern of
`%N = ..$attrs $op`. The value produced by the instruction may be
omitted if instruction is used purely for side-effect. Operations
follow the pattern of `$opname[..$types] ..$values`.

Low-level ops:

1. **Call**: `call[$type] $ptrvalue(..$values)`

   Calls given function of given function type and argument values.
   Corresponds to LLVM's
   [`call`](http://llvm.org/docs/LangRef.html#call-instruction).

1. **Load**: `load[$type] $ptrvalue`

   Load value of given type from memory.
   Corresponds to LLVM's
   [`load`](http://llvm.org/docs/LangRef.html#load-instruction).

1. **Store**: `store[$type] $ptrvalue, $value`

   Store value of given type to memory.
   Corresponds to LLVM's
   [`store`](http://llvm.org/docs/LangRef.html#store-instruction).

1. **Elem**: `elem[$type] $ptrvalue, ..$indexes`

   Compute derived pointer starting from given pointer value.
   Corresponds to LLVM's
   [`getelementptr`](http://llvm.org/docs/LangRef.html#getelementptr-instruction).

1. **Extract**: `extract[$type] $aggrvalue, $index`

   Extract element from aggregate value.
   Corresponds to LLVM's
   [`extractvalue`](http://llvm.org/docs/LangRef.html#extractvalue-instruction).

1. **Insert**: `insert[$type] $aggrvalue, $value, $index`

   Create a new aggregate value based on existing one with element at index replaced with new value.
   Corresponds to LLVM's
   [`insertvalue`](http://llvm.org/docs/LangRef.html#insertvalue-instruction).

1. **Alloca**: `alloca[$type]`

   Stack allocate a slot of memory big enough to store given type.
   Corresponds to LLVM's
   [`alloca`](http://llvm.org/docs/LangRef.html#alloca-instruction).

1. **Binary**: `$bin[$type] $value1, $value2`

   Where `$bin` is one of the following:
   `add`, `sub`, `mul`, `div`, `mod`, `shl`, `lshr`
   `ashr`, `and`, `or`, `xor`. Depending on the type, maps
   to either integer or floating point
   [binary operation](http://llvm.org/docs/LangRef.html#binary-operations) in LLVM.

1. **Comparison**: `$comp[$type] $value1, $value2`

   Where `$comp` is one of the following: `eq`, `neq`, `lt`, `lte`, `gt`, `gte`.
   Depending on the type, maps to either
   [`icmp`](http://llvm.org/docs/LangRef.html#icmp-instruction) or
   [`fcmp`](http://llvm.org/docs/LangRef.html#fcmp-instruction) with corresponding
   comparison flags in LLVM.

1. **Conversion**: `$conv[$type] $value`

   Where `$conv` is one of the following: `trunc`, `zext`, `sext`, `fptrunc`,
   `fpext`, `fptoui`, `fptosi`, `uitofp`, `sitofp`, `ptrtoint`, `inttoptr`, `bitcast`.
   Corresponds to LLVM
   [conversion instruction](http://llvm.org/docs/LangRef.html#conversion-operations)
   with the same name.

High-level ops:

1. **Size**: `size[$type]`

   Returns a size of given type.

1. **Allocate class**: `alloc[class @$name]`

   Roughly corresponds to `new $name` in Scala.
   Performs allocation without calling the constructor.

1. **Get pointer to field**: `field-elem[$type] $value, @$name`

   Returns a pointer to the given field of given object.

1. **Get pointer to method**: `method-elem[$type] $value, @$name`

   Returns a pointer to the given method of given object.

1. **As instance of**: `as[$type] $value`

   Corresponds to `$value.asInstanceOf[$type]` in Scala.

1. **Is instance of**: `is[$type] $value`

   Corresponds to `$value.isInstanceOf[$type]` in Scala.

1. **Allocate array**: `arr-alloc[$type] $value`

   Corresponds to `new Array[$type]($value)` in Scala.

1. **Array length**: `arr-length $value`

   Corresponds to `$value.length` in Scala.

1. **Get pointer to array element**: `arr-elem[$type] $value, $index`

   Returns a pointer to the element with given index in the array.

## Values

Low-level values:

1. **Boolean**: `true`, `false`

   Corresponds to LLVM's `true` and `false`.

1. **Zero**: `zero $type`

   Corresponds to LLVM's `zeroinitializer`.

1. **Integer**: `Ni8`, `Ni16`, `Ni32`, `Ni64`

   Correponds to LLVM's integer values.

1. **Floating point**: `N.Nf32`, `N.Nf64`

   Corresponds to LLVM's floating point values.

1. **Struct**: `struct @$name {..$values}`

   Corresponds to LLVM's struct values.

1. **Array**: `array $ty {..$values}`

   Corresponds to LLVM's array value.

1. **Local reference**: `%N`

   Named reference to result of previously executed
   instructions or basic block parameters.

1. **Global reference**: `@$name`

   Reference to the value of top-level definition.

1. **Intrinsic reference**: `#$name`

   Reference to [intrinsic](intrinsics.md) definition.

High-level values:

1. **Unit value**: `unit`

   Corresponds to `()` in Scala.

1. **Null value**: `null`

   Corresponds to null literal in Scala.

1. **String value**: `"..."`

   Corresponds to string literal in Scala.

1. **Reflection class value**: `class $type`

   Corresponds to `classOf[$type]` in Scala.

## Attributes

Attributes allow one to attach additional metadata to definitions and instructions.

* **Inlinining**: `inline($advice)`

  Where `advice` is one of: `must`, `no`, `hint`
  Applicable to functions.
  Corresponds to LLVM's inlinining attributes.

* **Overrides**: `overrides(@$name)`

  Applicable to methods.
  Must be used whenever a method overrides another one in parent class.

* **Implements**: `implements(@$name)`

  Applicable to methods that implement an interface method.

* **Unsigned**: `usgn`

  Used on binary operations to signify that integer values should be treated as unsigned.
