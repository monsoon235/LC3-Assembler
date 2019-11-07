package Assembler

//internal enum class Type {
//    ADD, AND, NOT,
//    BR, RET, JMP, JSR, JSRR, RTI,
//    LD, LDR, LDI, LEA, ST, STR, STI,
//    TRAP,
//    _START, _END, _FILL, _BLKW, _STRINGZ,
//    GETC, OUT, PUTS, IN, PUTSP, HALT
//}

/**
 * 字段类型枚举
 */
internal enum class WordType {
    Inst,
    BRInst,     //BR指令需要特殊对待
    TrapInst,
    PseudoInst,
    Reg,

    Number,
    Label,
    String,
    Unknown
}

/**
 * 指令对象
 * @param asm 分割字段的汇编码
 * @param originalText 原始汇编码
 * @param originalLineNumber 此条指令在原汇编码文件的行序号
 */
class Instruction(private var asm: Array<String>,
                  val originalText: String,
                  val originalLineNumber: Int) {

    var addrLabel: String? = null       //指令前的地址label
    var addr = -1                       //这条指令的开始地址
    var length = 1                     //指令翻译成机器码的长度，默认是1，只有.BLKW和.STRINGZ需要更改

    var Inst: String                    //指令

    private var is_imm = false          //ADD AND 是否是立即数型
    private var SR1 = -1                //SR1
    private var SR2 = -1                //SR2
    private var SR = -1                 //SR
    private var DR = -1                 //DR
    private var BaseR = -1              //BaseR
    var imm = -1                        //立即数
    private var string: String? = null  //.STRINGZ后的字符串
    private var label: String? = null   //指令中包含的label

    /**
     * 构造函数
     */
    init {

        //分析各个字段的类型
        val wordType = ArrayList<WordType>()

        asm.forEachIndexed { i, s ->
            when (s.toUpperCase()) {
                in InstOpCodeTable -> {
                    asm[i] = s.toUpperCase()
                    wordType.add(WordType.Inst)
                }
                in BRnzpTable -> {
                    asm[i] = s.toUpperCase()
                    wordType.add(WordType.BRInst)
                }
                in TrapVectorTable -> {
                    asm[i] = s.toUpperCase()
                    wordType.add(WordType.TrapInst)
                }
                in PseudoInstTable -> {
                    asm[i] = s.toUpperCase()
                    wordType.add(WordType.PseudoInst)
                }
                in RegTable -> {
                    asm[i] = s.toUpperCase()
                    wordType.add(WordType.Reg)
                }

                //需要进行正则匹配，确定是否合法
                else -> {
                    if (s.matches(Regex("^[xX][\\dA-Fa-f]+$"))) {
                        wordType.add(WordType.Number)
                    } else if (s.matches(Regex("^#-?\\d+$"))) {
                        wordType.add(WordType.Number)
                    } else if ((s.first() == '\'' || s.first() == '"') && s.last() == s.first()) {
                        wordType.add(WordType.String)
                    } else if (s.matches(Regex("^[A-Za-z_]\\w*$"))) {
                        wordType.add(WordType.Label)
                    } else {
                        throwInstructionSyntaxException()
                    }

                }
            }
        }

        val instIndex = wordType.indexOfFirst {
            it == WordType.Inst || it == WordType.TrapInst
                    || it == WordType.BRInst || it == WordType.PseudoInst
        }

        when (instIndex) {
            0 -> {
                //do nothing
            }
            1 -> {
                if (wordType[0] == WordType.Label) {
                    //移除label，方便下面的解析
                    addrLabel = asm[0]
                    wordType.removeAt(0)
                    asm = asm.copyOfRange(1, asm.size)
                } else {
                    throwInstructionSyntaxException()
                }
            }
            else -> {
                throwInstructionSyntaxException()
            }
        }

        Inst = asm[0]

        //指令解析
        when (Inst) {

            in TrapVectorTable -> {
                if (asm.size == 1) {
                    //TRAP指令转化回TRAP
                    Inst = "TRAP"
                    imm = TrapVectorTable[asm[0]] as Int
                } else {
                    throwInstructionSyntaxException()
                }
            }

            "ADD", "AND" -> {
                if (asm.size == 4
                        && wordType[1] == WordType.Reg
                        && wordType[2] == WordType.Reg) {
                    DR = RegTable[asm[1]] as Int
                    SR1 = RegTable[asm[2]] as Int
                    when (wordType[3]) {
                        WordType.Reg -> {
                            is_imm = false
                            SR2 = RegTable[asm[3]] as Int
                        }
                        WordType.Number -> {
                            is_imm = true
                            imm = parseNumber(asm[3])
                        }
                        else -> throwInstructionSyntaxException()
                    }
                } else {
                    throwInstructionSyntaxException()
                }
            }

            "NOT" -> {
                if (asm.size == 3
                        && wordType[1] == WordType.Reg
                        && wordType[2] == WordType.Reg) {
                    DR = RegTable[asm[1]] as Int
                    SR1 = RegTable[asm[2]] as Int
                } else {
                    throwInstructionSyntaxException()
                }
            }

            "RET" -> {
                if (asm.size == 1) {
                    Inst = "JMP"
                    BaseR = 7
                } else {
                    throwInstructionSyntaxException()
                }
            }

            "RTI", ".END" -> {
                if (asm.size != 1) {
                    throwInstructionSyntaxException()
                }
            }

            "JSR", in BRnzpTable -> {
                if (asm.size == 2) {
                    when (wordType[1]) {
                        WordType.Number -> imm = parseNumber(asm[1])
                        WordType.Label -> label = asm[1]
                        else -> throwInstructionSyntaxException()
                    }
                } else {
                    throwInstructionSyntaxException()
                }
            }

            "JMP", "JSRR" -> {
                if (asm.size == 2
                        && wordType[1] == WordType.Reg) {
                    BaseR = RegTable[asm[1]] as Int
                } else {
                    throwInstructionSyntaxException()
                }
            }

            "LD", "LDI", "ST", "STI", "LEA" -> {
                if (asm.size == 3
                        && wordType[1] == WordType.Reg) {
                    when (Inst) {
                        "LD", "LDI", "LEA" -> DR = RegTable[asm[1]] as Int
                        "ST", "STI" -> SR = RegTable[asm[1]] as Int
                    }
                    when (wordType[2]) {
                        WordType.Number -> imm = parseNumber(asm[2])
                        WordType.Label -> label = asm[2]
                        else -> throwInstructionSyntaxException()
                    }
                } else {
                    throwInstructionSyntaxException()
                }
            }

            "LDR", "STR" -> {
                if (asm.size == 4
                        && wordType[1] == WordType.Reg
                        && wordType[2] == WordType.Reg
                        && wordType[3] == WordType.Number) {
                    when (Inst) {
                        "LDR" -> DR = RegTable[asm[1]] as Int
                        "STR" -> SR = RegTable[asm[1]] as Int
                    }
                    BaseR = RegTable[asm[2]] as Int
                    imm = parseNumber(asm[3])
                } else {
                    throwInstructionSyntaxException()
                }
            }

            "TRAP", ".FILL", ".BLKW" -> {
                if (asm.size == 2
                        && wordType[1] == WordType.Number) {
                    imm = parseNumber(asm[1])
                    if (Inst == ".BLKW") {
                        if (imm in 1..Short.MAX_VALUE) {
                            length = imm
                        } else {
                            throwImmediateException(1, Short.MAX_VALUE.toInt())
                        }
                    }
                } else {
                    throwInstructionSyntaxException()
                }
            }
            ".ORIG" -> {
                if (asm.size == 1) {
                    imm = 0x3000
                } else if (asm.size == 2 && wordType[1] == WordType.Number) {
                    imm = parseNumber(asm[1])
                } else {
                    throwInstructionSyntaxException()
                }
            }
            ".STRINGZ" -> {
                if (asm.size == 2
                        && wordType[1] == WordType.String) {
                    string = asm[1].substring(1, asm[1].length - 1)
                    length = string!!.length + 1
                } else {
                    throwInstructionSyntaxException()
                }
            }
            else -> {
                throwInstructionSyntaxException()
            }
        }

    }

    /**
     * 转化为机器码
     */
    fun toMachineCode(): ShortArray {
        //Inst已经转化成了大写
        when (Inst) {

            in InstOpCodeTable -> {
                var code = (InstOpCodeTable[Inst] as Int) shl 12
                when (Inst) {

                    "ADD", "AND" -> {
                        code += DR shl 9
                        code += SR1 shl 6
                        if (is_imm) {
                            code += 1 shl 5
                            if (imm in -16..15) {
                                code += imm and 0b11_111
                            } else {
                                throwImmediateException(-16, 15)
                            }
                        } else {
                            code += SR2
                        }
                    }

                    "NOT" -> {
                        code += DR shl 9
                        code += SR1 shl 6
                        code += 0b111111
                    }

                    "JMP", "RET", "JSRR" -> {
                        code += BaseR shl 6
                    }

                    "JSR" -> {
                        code += 1 shl 11
                        if (imm in -1024..1023) {
                            code += imm and 0b11_111_111_111
                        } else {
                            throwImmediateException(-1024, 1023)
                        }
                    }

                    "RTI" -> {
                        //do nothing
                    }

                    "LD", "LDI", "LEA", "ST", "STI" -> {
                        when (Inst) {
                            "LD", "LDI", "LEA" -> code += DR shl 9
                            "ST", "STI" -> code += SR shl 9
                        }
                        if (imm in -256..255) {
                            code += imm and 0b111_111_111
                        } else {
                            throwImmediateException(-256, 255)
                        }
                    }

                    "LDR", "STR" -> {
                        when (Inst) {
                            "LDR" -> code += DR shl 9
                            "STR" -> code += SR shl 9
                        }
                        code += BaseR shl 6
                        if (imm in -32..31) {
                            code += imm and 0b111_111
                        } else {
                            throwImmediateException(-32, 31)
                        }
                    }

                    "TRAP" -> {
                        if (imm in 0..255) {
                            code += imm and 0b11_111_111
                        } else {
                            throwImmediateException(0, 255)
                        }
                    }

                }

                return shortArrayOf(code.toShort())
            }

            in BRnzpTable -> {
                var code = (BRnzpTable[Inst] as Int) shl 9
                if (imm in -256..255) {
                    code += imm and 0b111_111_111
                } else {
                    throwImmediateException(-256, 255)
                }
                return shortArrayOf(code.toShort())
            }

            in PseudoInstTable -> {
                when (Inst) {
                    ".FILL" -> {
                        if (imm in -32768..32767) {
                            return shortArrayOf(imm.toShort())
                        } else {
                            throwImmediateException(-32768, 32767)
                        }
                    }
                    ".BLKW" -> {
                        if (imm in 1..Short.MAX_VALUE) {
                            return ShortArray(imm) { 0 }
                        } else {
                            throwImmediateException(1, Short.MAX_VALUE.toInt())
                        }
                    }
                    ".STRINGZ" -> {
                        val tmp = ArrayList<Short>()
                        string!!.forEach { c ->
                            tmp.add(c.toShort())
                        }
                        tmp.add(0)
                        return tmp.toShortArray()
                    }
                    ".ORIG" -> throw AssemblyException(originalText, originalLineNumber,
                            "Duplicated .ORIG")
                    ".END" -> throw AssemblyException(originalText, originalLineNumber,
                            "Duplicated .END")
                }

            }
            else -> {
                throwInstructionSyntaxException()
            }
        }
        return shortArrayOf()
    }

    /**
     * 替换指令中的符号为 PCoffset
     */
    fun replaceLabel(symbolTable: HashMap<String, Int>) {
        if (label != null) {
            val tmp = symbolTable[label!!]
            if (tmp != null) {
                imm = tmp - (addr + 1)
            } else {
                throw AssemblyException(originalText, originalLineNumber,
                        "Can't find the label : $label")
            }
        }
    }

    /**
     * 解析数字
     */
    private fun parseNumber(str: String): Int {
        try {
            when (str.first()) {
                '#' -> {
                    return str.substring(1).toInt(10)
                }
                'x', 'X' -> {
                    return str.substring(1).toInt(16).toShort().toInt()
                }
            }
        } catch (e: Exception) {
        }
        throwInstructionSyntaxException()
        return -1
    }

    /**
     * 抛出立即数超出允许范围的异常
     */
    private fun throwImmediateException(min: Int, max: Int) {
        throw AssemblyException(originalText, originalLineNumber,
                "Immediate exceeds the allowed range : [$min, $max]")
    }

    private fun throwInstructionSyntaxException() {
        throw AssemblyException(originalText, originalLineNumber,
                "Instruction syntax error")
    }

}


//指令与操作码对照表
internal val InstOpCodeTable = hashMapOf(
        Pair("ADD", 0b0001),
        Pair("AND", 0b0101),
        Pair("NOT", 0b1001),
//        Pair("BRInst", 0b0000), //BR特殊对待
        Pair("RET", 0b1100),
        Pair("JMP", 0b1100),
        Pair("JSR", 0b0100),
        Pair("JSRR", 0b0100),
        Pair("RTI", 0b1000),
        Pair("LD", 0b0010),
        Pair("LDR", 0b0110),
        Pair("LDI", 0b1010),
        Pair("LEA", 0b1110),
        Pair("ST", 0b0011),
        Pair("STR", 0b0111),
        Pair("STI", 0b1011),
        Pair("TRAP", 0b1111)
)

//BR指令与nzp对照表
internal val BRnzpTable = hashMapOf(
        Pair("BR", 0b000),
        Pair("BRN", 0b100),
        Pair("BRZ", 0b010),
        Pair("BRP", 0b001),
        Pair("BRNZ", 0b110),
        Pair("BRZN", 0b110),
        Pair("BRNP", 0b101),
        Pair("BRPN", 0b101),
        Pair("BRZP", 0b011),
        Pair("BRPZ", 0b011),
        Pair("BRNZP", 0b111),
        Pair("BRNPZ", 0b111),
        Pair("BRZNP", 0b111),
        Pair("BRZPN", 0b111),
        Pair("BRPNZ", 0b111),
        Pair("BRPZN", 0b111)
)

//TRAP指令与TRAP vector对照表
internal val TrapVectorTable = hashMapOf(
        Pair("GETC", 0x20),
        Pair("OUT", 0x21),
        Pair("PUTS", 0x22),
        Pair("IN", 0x23),
        Pair("PUTSP", 0x24),
        Pair("HALT", 0x25)
)

//寄存器对照表
internal val RegTable = hashMapOf(
        Pair("R0", 0),
        Pair("R1", 1),
        Pair("R2", 2),
        Pair("R3", 3),
        Pair("R4", 4),
        Pair("R5", 5),
        Pair("R6", 6),
        Pair("R7", 7)
)

//汇编器指令表
internal val PseudoInstTable = hashSetOf(
        ".ORIG", ".END", ".FILL", ".BLKW", ".STRINGZ"
)
