# Concurrent Boolean Circuit Solver

## Overview
This project implements a concurrent solver for Boolean circuits. It evaluates Boolean expressions in parallel, supporting lazy evaluation to optimize computation. The solver ensures concurrent handling of multiple expressions and parallel computation of subexpressions.

## Circuit Structure and Node Types
A Boolean circuit is represented as a tree-like structure with nodes of the following types:
* LeafNode: Represents Boolean constants (true, false) or variables.
* NOT: Negates the value of its child node.
* AND: Returns true if all child nodes evaluate to true.
* OR: Returns true if at least one child node evaluates to true.
* IF(a, b, c): Evaluates b if a is true, otherwise evaluates c.
* GT (Greater Than): Returns true if at least x + 1 child nodes are true.
* LT (Less Than): Returns true if at most x - 1 child nodes are true. 

Each node type is represented by a subclass of CircuitNode, with getArgs() providing child nodes and additional methods for specific types (e.g., getThreshold() for threshold nodes).

## Usage
```bash
javac -d ../bin/ cp2024/*/*.java && java --class-path ../bin/ cp2024.demo.Demo
```