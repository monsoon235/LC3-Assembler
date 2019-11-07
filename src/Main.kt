package Assembler

import java.io.*

/**
 * main函数
 */
fun main(args: Array<String>) {
    if (args.size == 1) {
        val input = File(args[0])
        if (input.exists()) {
            assemble(input,
                    File("${input.parent}/${input.nameWithoutExtension}.obj"))
        } else {
            println("No input file")
        }
    } else {
        println("No input file")
    }

}

/**
 * 汇编函数
 * @param input 汇编码文件
 * @param output 输出obj文件
 */
fun assemble(input: File, output: File) {
    val fin = BufferedReader(FileReader(input))

    try {
        //格式化
        val insts = format(fin.readLines().toTypedArray())

        fin.close()

        //创建符号链接表
        val index = createSymbolTable(insts)

        val out = DataOutputStream(
                BufferedOutputStream(
                        FileOutputStream(output)))
        //生成机器码
        generateMachineCode(insts, index[0], index[1])
                .forEach {
                    out.writeShort(it.toInt())
                }
        out.close()
    } catch (e: AssemblyException) {
        println(e.message)
    }


}


/**
 * 格式化汇编码并转化为指令对象
 * 指令和寄存器忽略大小写
 * label和字符串大小写敏感
 * @param lines 未格式化的汇编码
 * @return 指令对象列表
 */
fun format(lines: Array<String>): ArrayList<Instruction> {
    val insts = ArrayList<Instruction>()

    //生成指令对象
    lines.forEachIndexed { lineNumber, it ->

        if (it.indexOf('"') != -1 || it.indexOf('\'') != -1) {

            //可能存在字符串,需要特殊处理

            var a = -1      //字符串左边界（包括）
            var b = -1      //字符串右边界（不包括）

            //寻找字符串的范围
            findStr@
            for (i in 0 until it.length) {
                if (a == -1) {
                    when (it[i]) {
                        ';' -> break@findStr
                        '\'', '"' -> {
                            a = i
                        }
                    }
                } else if (it[i] == it[a] && it[i - 1] != '\\') {
                    b = i + 1               //字符串内容结束
                    break@findStr

                }
            }

            //引号在注释中，忽略
            if (a == -1 && b == -1) {

                //去注释、首位空格
                val str = it.replace(Regex("(\\s*;.*$)|(^\\s+)|(\\s+$)"), "")
                //分割字段
                if (str.isNotEmpty()) {  //排除空行
                    //添加一条指令
                    insts.add(Instruction(str
                            .split(Regex("(\\s*,\\s*)|(\\s+)"))
                            .toTypedArray()
                            , it, lineNumber + 1))
                }

            } else {
                if (b == -1) {
                    throw AssemblyException(it, lineNumber + 1,
                            "\" not closed")
                }

                val left = it.substring(0, a)
                val string = it.substring(a, b)
                val right = it.substring(b)

                //字符串左右要有空格和别的字段隔离
                if (left.isNotEmpty() && !left.matches(Regex("^.*\\s$"))) {
                    throw AssemblyException(it, lineNumber + 1,
                            "Instruction syntax error")
                }
                if (right.isNotEmpty() && !right.matches(Regex("^\\s.*$"))) {
                    throw AssemblyException(it, lineNumber + 1,
                            "Instruction syntax error")
                }

                val words = ArrayList<String>()

                var tmp = left.replace(Regex("(\\s*;.*$)|(^\\s+)|(\\s+$)"), "")
                if (tmp.isNotEmpty()) {
                    words.addAll(tmp
                            .split(Regex("(\\s*,\\s*)|(\\s+)"))
                            .toTypedArray())
                }

                words.add(string)

                tmp = right.replace(Regex("(\\s*;.*$)|(^\\s+)|(\\s+$)"), "")
                if (tmp.isNotEmpty()) {
                    words.addAll(tmp
                            .split(Regex("(\\s*,\\s*)|(\\s+)"))
                            .toTypedArray())
                }

                insts.add(Instruction(words.toTypedArray(), it, lineNumber + 1))

            }

        } else {

            //去注释、首位空格
            val str = it.replace(Regex("(\\s*;.*$)|(^\\s+)|(\\s+$)"), "")
            //分割字段
            if (str.isNotEmpty()) {  //排除空行
                //添加一条指令
                insts.add(Instruction(str
                        .split(Regex("(\\s*,\\s*)|(\\s+)"))
                        .toTypedArray()
                        , it, lineNumber + 1))
            }
        }

    }
    return insts
}


/**
 * 创建符号链接表，并把指令中的label进行替换
 * @param insts 指令对象列表
 * @return 第一条指令的地址
 */
fun createSymbolTable(insts: ArrayList<Instruction>): Array<Int> {
    val origIndex = insts.indexOfFirst {
        it.Inst == ".ORIG"
    }
    if (origIndex == -1) {
        throw AssemblyException(null, null,
                "Can't find .ORIG")
    }

    val endIndex = insts.indexOfLast {
        it.Inst == ".END"
    }
    if (endIndex == -1) {
        throw AssemblyException(null, null,
                "Can't find .END")
    }

    if (origIndex >= endIndex) {
        throw AssemblyException(null, null,
                "No valid instructions between .ORIG and .END")
    }

    //获得起始地址
    var addr = insts[origIndex].imm

    val table = HashMap<String, Int>()
    //创建符号链接表
    for (i in origIndex + 1 until endIndex) {
        insts[i].addr = addr
        if (insts[i].addrLabel != null) {
            if (insts[i].addrLabel in table) {
                throw AssemblyException(
                        insts[i].originalText,
                        insts[i].originalLineNumber,
                        "Duplicated label : ${insts[i].addrLabel}")
            } else {
                //添加一个label
                table[insts[i].addrLabel!!] = addr
            }
        }
        addr += insts[i].length
    }

    //替换指令中的label
    for (i in origIndex + 1 until endIndex) {
        insts[i].replaceLabel(table)
    }

    return arrayOf(origIndex, endIndex)
}

/**
 * 生成机器码
 * @param insts 指令对象列表,去除了.START和.END,更新了label
 * @param origIndex 汇编开始处
 * @param endIndex 汇编结束处
 * @return 机器码序列
 */
fun generateMachineCode(insts: ArrayList<Instruction>,
                        origIndex: Int,
                        endIndex: Int): ArrayList<Short> {

    val array = arrayListOf(insts[origIndex].imm.toShort())
    for (i in origIndex + 1 until endIndex) {
        insts[i].toMachineCode()
                .forEach {
                    array.add(it)
                }
    }
    return array
}
