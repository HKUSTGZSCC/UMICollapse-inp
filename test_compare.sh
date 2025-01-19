#!/bin/bash

# 配置
NUM_RUNS=5  # 运行次数
CORE1=0,2,4,6,8    # 第一个进程绑定的 CPU 核心
CORE2=1,3,5,7,9    # 第二个进程绑定的 CPU 核心

# 命令
COMMAND1="taskset -c $CORE1 java -jar /home/shan/workplace/ASC/UMICollapse/umicollapse.jar bam -i ../UMICollapse-inp/test/SRR23538292_1_100.bam -o ../UMICollapse-inp/test/dedup_92_100_org.bam --data bktree"
COMMAND2="taskset -c $CORE2 java --add-modules jdk.incubator.vector -jar /home/shan/workplace/ASC/UMICollapse-inp/umicollapse.jar bam -i test/SRR23538292_1_100.bam -o test/dedup_92_100.bam --data bktree --quick-io"

# 函数：运行命令并记录时间
run_command() {
    local command=$1
    local output_file=$2
    local run_id=$3

    echo "Running: $command"
    # 使用 time 命令，提取 real 时间并转换为秒
    { time $command; } 2>&1 | grep real | awk '{split($2, a, "m"); split(a[2], b, "s"); print a[1]*60 + b[1]}' >> "${output_file}_${run_id}.txt"
}

# 清理旧的时间记录文件
rm -f command1_times_*.txt command2_times_*.txt

# 并行运行两个命令
for ((i=1; i<=NUM_RUNS; i++)); do
    echo "Run $i..."
    run_command "$COMMAND1" "command1_times" "$i" &
    run_command "$COMMAND2" "command2_times" "$i" &
    wait  # 等待两个命令完成
done

# 合并时间记录文件
cat command1_times_*.txt > command1_times.txt
cat command2_times_*.txt > command2_times.txt

# 计算平均时间
calculate_average() {
    local file=$1
    awk '{ total += $1; count++ } END { print total/count }' "$file"
}

# 输出结果
echo "Average time for command 1: $(calculate_average command1_times.txt) seconds"
echo "Average time for command 2: $(calculate_average command2_times.txt) seconds"

# 清理临时文件
rm -f command1_times_*.txt command2_times_*.txt