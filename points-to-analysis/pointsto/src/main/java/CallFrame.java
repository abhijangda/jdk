import java.io.IOException;

import org.apache.bcel.classfile.*;
import org.apache.bcel.*;
import org.apache.bcel.generic.Type;
import org.apache.bcel.util.*;
import java.util.jar.*;

import javax.print.attribute.IntegerSyntax;
import javax.swing.plaf.basic.BasicInternalFrameTitlePane.SystemMenuBar;
import java.nio.file.*;
import java.util.*;
import java.util.function.IntToLongFunction;
import java.io.*;
import java.util.zip.ZipInputStream;

public class CallFrame {
  JavaMethod method;
  int bci;
  long[] localVarValue;

  CallFrame(JavaMethod m, int b) {
    method = m;
    bci = b;
    localVarValue = new long[m.getMethod().getLocalVariableTable().getLength()];
  }
}