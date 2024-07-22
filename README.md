## 黑马点评（Redis项目）

## 1. 登录相关

### 1.1 登录用户信息

* ***校验用户信息***

  用户登录后，后续的http请求会携带cookie，cookie中携带session_id

  > cookie保存在客户端
  >
  > session保存在服务端

  *业务流程图（简易）*

  <img src="https://txcould-image-1318385221.cos.ap-nanjing.myqcloud.com/image/image-20240717170356833.png" alt="image-20240717170356833" style="zoom:75%;" />

    1. 设置拦截器

       Spring 提供了`HandlerInterceptor`接口，实现其中的`preHandler`方法，在每个http请求之前来校验session信息，确定是否为登录用户

    2. 为后续的服务保存一个`ThreadLocal`变量保存登录信息，从无需再次校验用户登录

       > session是在tomcat服务器内存存储的，应该使用DTO对象来存储，减少内存消耗，以及用户敏感信息泄露的风险
       >
       > ![image-20240717175303865](https://txcould-image-1318385221.cos.ap-nanjing.myqcloud.com/image/image-20240717175303865.png)

    3. 将session中存储的User对象变为UserDTO对象，减少内存的消耗，同时保护用户的敏感信息



*问题：当我们的tomcat服务器是分布式的时候session失效*

:star:*解决：将验证码以及用户信息保存到Redis中*

* ***校验用户信息，基于Redis***

  <img src="https://txcould-image-1318385221.cos.ap-nanjing.myqcloud.com/image/image-20240719014036079.png" alt="image-20240719014036079" style="zoom:50%;" />

    1. key的设置，要保证唯一性，验证码与手机号绑定，而token通过UUID生成
    2. 数据类型的选择：code->String，token->Hash
    3. 创建时设定TTL，但是拦截器中每次会刷新token的TTL，保证token不过期

### 1.2 拦截器优化登录

<font color=#0099ff>*问题：第一个拦截器并非拦截一切路径*</font>

*:star:解决：新增一个全局拦截器，如果用户处于登录态（即ThreadLocal中保存了用户信息），我们就刷新Redis的TTL*

![image-20240719090146167](https://txcould-image-1318385221.cos.ap-nanjing.myqcloud.com/image/image-20240719090146167.png)

同时因为多个拦截器，所以要设置好优先级order，0最大

## 2. 商户相关

### 2.1 将商户信息以及商户类型写入缓存



### 2.2 缓存更新策略

![image-20240719141214792](https://txcould-image-1318385221.cos.ap-nanjing.myqcloud.com/image/image-20240719141214792.png)

*策略*

* 主动更新策略：**更新数据库后，删除redis缓存**，因为如果更新数据库就写入缓存，无效的写操作会很多，因为这些写操作如果没有人查询是无效的
  * 问题：如何保证**数据库和删除缓存的原子性**
    1. 单机系统，利用JMM保证了有序性
    2. 分布式系统，需要TCC来支持
* 内存淘汰：针对那些极少更新的数据
* 超时剔除：作为主动更新的兜底

### 2.3 缓存穿透

穿透：redis+mysql

*解决方案*

1. 缓存空对象
   * 优点：实现简单
   * 缺点：内存消耗/短时间的不一致（通过添加TTL）
2. bloom filter
   * 优点：内存消耗小（二进制位+多个hash）
   * 缺点：实现复杂/存在假阳性的问题

*改进*

1. 增强id的复杂性（路径参数）
2. 做一些基础的数据格式校验

### 2.4 缓存雪崩

*场景*：大量key同时失效或者redis服务器宕机

*解决方案*

1. 随机TTL
2. 集群
3. 降级限流策略
4. 多级缓存

### 2.5 缓存击穿

击穿：热点key**过期**，并且**重建业务**较为复杂的key，导致的大量请求

*解决方案*：

1. 互斥锁

   * 优点：**一致性** / 实现简单
   * 缺点：线程需要等待 / 存在死锁风险

2. 逻辑过期

   <img src="https://txcould-image-1318385221.cos.ap-nanjing.myqcloud.com/image/image-20240719154026637.png" alt="image-20240719154026637" style="zoom:50%;" />

   <img src="https://txcould-image-1318385221.cos.ap-nanjing.myqcloud.com/image/image-20240719153708854.png" alt="image-20240719153708854" style="zoom:50%;" />

   * 优点：线程无需等待，**可用性更好**
   * 缺点：弱一致性 / 额外的内存消耗（需要维护逻辑过期字段）

#### 2.5.1 互斥锁方案

<img src="https://txcould-image-1318385221.cos.ap-nanjing.myqcloud.com/image/image-20240720114547352.png" alt="image-20240720114547352" style="zoom:50%;" />

* 锁的选择

  这里利用redis中的`setnx`方法（java中的`setIfAbsent`方法），只有key不存在时才能创建成功，因为在分布式系统下不能使用synchronized作为锁。

  而redis本身的操作又是单线程的，保证了锁的唯一性。

  将对象前缀+对象id作为key，保证了高并发

* 锁的释放

  为了防止死锁出现，使用try-finally结构，保证一定要释放锁，同时给锁设置过期时间

#### 2.5.2 逻辑过期

<img src="https://txcould-image-1318385221.cos.ap-nanjing.myqcloud.com/image/image-20240719213928490.png" alt="image-20240719213928490" style="zoom: 50%;" />

* 不同于，互斥锁的方法，逻辑过期保证了更好的可用性（没有死锁，没有线程等待），但是key永不过期，如果在高并发场景下，在查询数据库线程回写redis期间会导致数据的不一致

* 线程池：

  >  根据阿里巴巴开发手册，必须通过线程池获取线程，而非自己创建线程

  ```java
      private static final ThreadPoolExecutor CACHE_REBUILD_EXECUTOR = new ThreadPoolExecutor(
              2,
              5,
              2L,
              TimeUnit.SECONDS,
              new ArrayBlockingQueue<>(3)
              );
  ```

### 2.6 Utils

将之前解决缓存击穿，缓存穿透的解决方案，利用泛型编程整合成工具类

- [x] 完善RedisUtils

## 3.秒杀系统

### 3.1 全局唯一ID

**分布式系统**下的全局唯一ID，要保证

* 唯一性
* 高可用：使用集群
* 递增性：
* 高性能：快速生成
* 安全性：不泄露生成规律

以Long，8字节作为ID号，如下定义

<img src="https://txcould-image-1318385221.cos.ap-nanjing.myqcloud.com/image/image-20240720120951878.png" alt="image-20240720120951878" style="zoom:50%;" />

> 还有其他方案

### 3.2 基本下单

***问题：超卖问题***

<img src="https://txcould-image-1318385221.cos.ap-nanjing.myqcloud.com/image/image-20240720162446204.png" alt="image-20240720162446204" style="zoom:50%;" />

### 3.3 CAS解决超卖问题

*核心代码*

```java
// 3.减少库存，CAS方案，判断当前的库存是否是之前查询到库存->update时库存必须大于0
boolean success = seckillVoucherService.update()
    .setSql("stock = stock - 1")
    .eq("voucher_id", voucherId).gt("stock", 0)
    .update();
```

*原理*

1. 本质是利用了mysql的锁机制，因为在RR隔离级别下，update操作默认会添加行锁中的排他锁，从而必然只会存在一个线程可以进行写操作
2. 同时需要创建订单，我们需要提交事务

### 3.4 一人一单问题

*原理*

* 利用之前保存在ThreadLocal中的userID，但是synchronized锁锁定的是jvm对象，在单机情况下，可以使用常量池中的String对象来实现对象的唯一性。
* 同时要注意`@Transactional`是通过代理实现事务的，应该要调用代理对象中的方法来实现事务

*核心代码*

```java
        Long userId = UserHolder.getUser().getId();
        // 注意锁释放和事务提交的先后顺序
        synchronized (userId.toString().intern()) {// 先将id转化为字符串，在从字符串常量池中查找，从而保证对象锁的正确
            // 获取代理对象
            // 1.如果直接调用方法，spring的事务会失效，因为spring是通过代理来实现事务的
            // 2.需要先获得当前的代理对象再通过代理对象来调用方法才能实现事务
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
```

### 3.5 分布式锁

在集群模式下，synchronized对象锁失效

因为在不同的tomcat服务器中维护着不同的jvm，这些jvm中的变量无法通讯。

*解决方案*

<img src="https://txcould-image-1318385221.cos.ap-nanjing.myqcloud.com/image/image-20240721124203796.png" alt="image-20240721124203796" style="zoom:50%;" />

* 依然是利用Redis的String作为锁，nx保证锁的唯一性，ex作为防止死锁的保底策略，执行完毕finally释放锁

```shell
set lock thread1 nx ex 5;

del lock;
```

#### 3.5.1 锁的误删问题

*情况1*

业务阻塞导致的锁超时误删

<img src="https://txcould-image-1318385221.cos.ap-nanjing.myqcloud.com/image/image-20240721124238596.png" alt="image-20240721124238596" style="zoom:50%;" />

> 注意，分布式场景下，这些线程不一定在同一个节点上，仅仅靠 ThreadID 无法保证线程的唯一性

* 在存入线程id时，我们需要区分不同的节点，使用`static final`类型的UUID来区分不同的节点
* 在释放锁的时机，检查锁中的value是否和当前线程id + 节点编号一致

*情况2*

![image-20240721135648051](https://txcould-image-1318385221.cos.ap-nanjing.myqcloud.com/image/image-20240721135648051.png)

要保证**判断锁标识和释放锁的原子性**

*解决方案*

* lua脚本

*总结*

1. 获取锁：setnx 操作来保证互斥，同时设置过期时间避免死锁，提高安全性
2. 释放锁：利用lua脚本来保证原子性，同时利用UUID + key来保证锁的唯一性，防止误删

#### 3.5.2 分布式锁的优化

* 可重入
* 重试机制
* 超时释放
* 主从一致性

> 可重入锁的基本原理：
>
> * 之前利用`setnx`命令获得锁，我们只会判断是否存在
>
> 记录重入次数 + 线程ID + 节点标识，判断即可实现可重入锁
>
> *结论*
>
> * 使用hash结构

#### 3.5.3 Redisson实现分布式锁

<img src="https://txcould-image-1318385221.cos.ap-nanjing.myqcloud.com/image/image-20240722004016265.png" alt="image-20240722004016265" style="zoom:50%;" />

- [ ] Redisson重读源码

*Redisson的实现方式*

1. 可重入锁：记录重入次数和线程id
2. 重试机制：优秀的重试机制while自旋，但是并非一直轮询，，sleep + 发布订阅 + 信号量功能，如果没有传入的leaseTime，会启用看门狗watchDog定期刷新ttl，从而一直尝试获取锁
3. 超时释放
4. 主从一致性：mutlilock，取消主从之分，保证每个主从都必须获得锁，执行完写入操作，释放锁。

## 4. 秒杀系统优化

*思路*

![image-20240722022442030](https://txcould-image-1318385221.cos.ap-nanjing.myqcloud.com/image/image-20240722022442030.png)

将 “减库存” “创建订单” 这样的mysql操作和主业务分离，而且将校验下单资格的任务全部放入redis中操作，从而提高性能

*设计数据字段*

1. 库存：String
2. 优惠卷对于的购买用户：Set集合

