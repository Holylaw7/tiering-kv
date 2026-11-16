# 参考文献（全文统一）

著录格式：**GB/T 7714-2025 顺序编码制**；按正文首次出现顺序连续编号。
说明：正文引用使用上标数字（如 <sup>[1]</sup>）；技术规范单列于文末，不占学术文献编号。
核对状态：**页码已全部核验**（2026-11-06，据出版社页面、会议论文集目录与 DBLP 记录）；
其中 Spanner 与 Percolator 均取自 OSDI 官方论文集目录（251 起，下一篇 265 起），
不以 USENIX 单篇页面 BibTeX 中不一致的页码为准。

## 一、学术文献（27 篇）

[1] O'NEIL P E, CHENG E, GAWLICK D, et al. The log-structured merge-tree (LSM-tree)[J].
        Acta Informatica, 1996, 33(4): 351-385. DOI: 10.1007/s002360050048.

[2] CHANG F, DEAN J, GHEMAWAT S, et al. Bigtable: a distributed storage system for structured data[C]//
        Proceedings of the 7th USENIX Symposium on Operating Systems Design and Implementation (OSDI).
        Berkeley: USENIX Association, 2006: 205-218.

[3] QIU J, YUAN F, GAO M, et al. HotRAP: hot record retention and promotion for LSM-trees with tiered storage[C]//
        Proceedings of the 2025 USENIX Annual Technical Conference (ATC). Berkeley: USENIX Association, 2025: 497-511.

[4] XU P, et al. RocksMash: building a fast and efficient LSM-tree store by integrating local storage with
        cloud storage[J]. ACM Transactions on Architecture and Code Optimization, 2022, 19(3). DOI: 10.1145/3527452.

[5] DONG H, ZHANG C, LI G, et al. Cloud-native databases: a survey[J]. IEEE Transactions on Knowledge and
        Data Engineering, 2024, 36(12): 7772-7791. DOI: 10.1109/TKDE.2024.3397508.

[6] ZHANG Q, et al. Rethinking data management systems for disaggregated data centers[C]//
        Proceedings of the 10th Biennial Conference on Innovative Data Systems Research (CIDR). 2020.

[7] DAYAN N, ATHANASSOULIS M, IDREOS S. Monkey: optimal navigable key-value store[C]//
        Proceedings of the 2017 ACM International Conference on Management of Data (SIGMOD). 2017: 79-94.
        DOI: 10.1145/3035918.3064054.

[8] DAYAN N, IDREOS S. Dostoevsky: better space-time trade-offs for LSM-tree based key-value stores via
        adaptive removal of superfluous merging[C]//Proceedings of the 2018 ACM SIGMOD. 2018.
        DOI: 10.1145/3183713.3196927.

[9] 吕萌, 华文镝, 谢平. 基于 LSM 树的键值存储系统技术研究综述[J]. 计算机科学, 2023, 50(8): 1-15.
        DOI: 10.11896/jsjkx.220900178.

[10] DECANDIA G, HASTORUN D, JAMPANI M, et al. Dynamo: Amazon's highly available key-value store[C]//
         Proceedings of the 21st ACM Symposium on Operating Systems Principles (SOSP). 2007: 205-220.

[11] ONGARO D, OUSTERHOUT J. In search of an understandable consensus algorithm[C]//
         Proceedings of the 2014 USENIX Annual Technical Conference (ATC). 2014: 305-319.

[12] CORBETT J C, DEAN J, EPSTEIN M, et al. Spanner: Google's globally-distributed database[C]//
         Proceedings of the 10th USENIX Symposium on Operating Systems Design and Implementation (OSDI). 2012: 251-264.

[13] PENG D, DABEK F. Large-scale incremental processing using distributed transactions and notifications[C]//
         Proceedings of the 9th USENIX Symposium on Operating Systems Design and Implementation (OSDI). 2010: 251-264.

[14] KULKARNI S S, DEMIRBAS M, MADAPPA D, et al. Logical physical clocks[C]//
         Proceedings of the 18th International Conference on Principles of Distributed Systems (OPODIS),
         LNCS 8878. Cham: Springer, 2014: 17-32.

[15] LI B, et al. Enjoy your observability: an industrial survey of microservice tracing and analysis[J].
         Empirical Software Engineering, 2022, 27(1): 25. DOI: 10.1007/s10664-021-10063-9.

[16] 张海藩, 牟永敏. 软件工程导论（第6版）[M]. 北京: 清华大学出版社, 2013.

[17] 黄健宏. Redis设计与实现[M]. 北京: 机械工业出版社, 2014.

[18] 汪云飞. Java EE开发的颠覆者: Spring Boot实战[M]. 北京: 电子工业出版社, 2016.

[19] BLOOM B H. Space/time trade-offs in hash coding with allowable errors[J].
         Communications of the ACM, 1970, 13(7): 422-426.

[20] MEGIDDO N, MODHA D S. ARC: a self-tuning, low overhead replacement cache[C]//
         Proceedings of the 2nd USENIX Conference on File and Storage Technologies (FAST). 2003.

[21] 李智慧. 大型网站技术架构: 核心原理与案例分析[M]. 北京: 电子工业出版社, 2013.

[22] 霍春阳. Vue.js设计与实现[M]. 北京: 人民邮电出版社, 2022.

[23] 姜承尧. MySQL技术内幕: InnoDB存储引擎（第2版）[M]. 北京: 机械工业出版社, 2013.

[24] KLEPPMANN M. Designing data-intensive applications[M]. Sebastopol: O'Reilly Media, 2017.

[25] BERNSTEIN P A, HADZILACOS V, GOODMAN N. Concurrency control and recovery in database systems[M].
         Reading: Addison-Wesley, 1987.

[26] GRAY J, REUTER A. Transaction processing: concepts and techniques[M]. San Francisco: Morgan Kaufmann, 1992.

[27] 王珊, 萨师煊. 数据库系统概论（第5版）[M]. 北京: 高等教育出版社, 2014.

## 二、技术规范（单列，不计入学术文献）

[S1] Redis. Redis protocol specification (RESP)[EB/OL]. https://redis.io/docs/latest/develop/reference/protocol-spec/.

[S2] Netty Project. Netty documentation[EB/OL]. https://netty.io/.

> 说明：文献 [16]–[18]、[21]–[23]、[27] 为中文专著与教材，分别对应需求分析、Redis 协议、
> Spring Boot 控制面、系统架构、Vue 前端、MySQL 存储引擎与数据库设计等章节的具体论述；
> 其余为第一章与第 2–5 章引用的经典文献。