## 黑马点评（Redis项目）

### 1. 登录相关

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

### 2. 拦截器优化

<font color=#0099ff>*问题：第一个拦截器并非拦截一切路径*</font>

*:star:解决：新增一个全局拦截器，如果用户处于登录态（即ThreadLocal中保存了用户信息），我们就刷新Redis的TTL*