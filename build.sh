# 创建 bin 目录（如果已存在则跳过）
mkdir -p bin

# 编译 Java 文件，指定 JDK 23，启用孵化模块，并添加依赖库
javac --release 23 --add-modules jdk.incubator.vector -cp "lib/htsjdk-2.19.0.jar:lib/snappy-java-1.1.7.3.jar" -d bin src/umicollapse/*/*.java src/test/*.java

# 进入 bin 目录
cd bin

# 创建 JAR 文件，包含 umicollapse 和 test 目录下的类文件
jar -c -m ../Manifest.txt -f ../umicollapse.jar umicollapse/*/*.class test/*.class

# 返回上级目录
cd ..

# 输出完成信息
echo "Done!"