#!/bin/bash

# 配置
NUM_RUNS=10  # 总运行次数（每个命令运行 NUM_RUNS/2 次）
COMMAND_WITH_QUICK_IO="java --add-modules jdk.incubator.vector -jar umicollapse.jar bam -i test/SRR23538292_1_100.bam -o test/dedup_92_100.bam --data bktree --quick-io"
COMMAND_WITHOUT_QUICK_IO="java --add-modules jdk.incubator.vector -jar umicollapse.jar bam -i test/SRR23538292_1_100.bam -o test/dedup_92_100.bam --data bktree"

# 函数：运行命令并记录时间
run_command() {
    local command=$1
    local output_file=$2

    echo "Running: $command"
    # 使用 time 命令，提取 real 时间并转换为秒
    { time $command; } 2>&1 | grep real | awk '{split($2, a, "m"); split(a[2], b, "s"); print a[1]*60 + b[1]}' >> "$output_file"
}

# 清理旧的时间记录文件
rm -f with_quick_io_times.txt without_quick_io_times.txt

# 交替运行带 --quick-io 和不带 --quick-io 的命令
for ((i=1; i<=NUM_RUNS; i++)); do
    echo "Run $i..."
    if ((i % 2 == 1)); then
        echo "Running with --quick-io..."
        run_command "$COMMAND_WITH_QUICK_IO" "with_quick_io_times.txt"
    else
        echo "Running without --quick-io..."
        run_command "$COMMAND_WITHOUT_QUICK_IO" "without_quick_io_times.txt"
    fi
done

# 计算平均时间
calculate_average() {
    local file=$1
    awk '{ total += $1; count++ } END { print total/count }' "$file"
}

# 输出结果
echo "Average time with --quick-io: $(calculate_average with_quick_io_times.txt) seconds"
echo "Average time without --quick-io: $(calculate_average without_quick_io_times.txt) seconds"

# Results
# Average time with --quick-io: 13.3855 seconds
# Average time without --quick-io: 14.3695 seconds