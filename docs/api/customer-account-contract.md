# Hợp đồng Customer và Account — Tuần 2, bước 2

Tài liệu chốt thiết kế ở bước 2 để triển khai ở bước 3–8. Bước 3 đã triển khai hai API Customer và security riêng; bước 4 đã triển khai adapter Core và xác định owner qua `/me`; bước 5 đã triển khai ba API Account và DTO response. Giữ các đường dẫn `/api/customers` và `/api/accounts` theo kế hoạch tuần 2; chưa thêm tiền tố phiên bản. Xem [hướng dẫn Customer](../customer-service.md), [tích hợp Core → Customer](../core-customer-integration.md) và [Account APIs](../account-api.md) để cấu hình và kiểm thử.

## 1. Phạm vi và quyền sở hữu dữ liệu

| API | Service sở hữu | Thành công |
|---|---|---|
| `POST /api/customers` | customer-service | `201`, `CustomerResponse` |
| `GET /api/customers/me` | customer-service | `200`, `CustomerResponse` |
| `POST /api/accounts` | core-banking-service | `201`, `AccountResponse` |
| `GET /api/accounts/{accountId}` | core-banking-service | `200`, `AccountResponse` |
| `GET /api/accounts/{accountId}/balance` | core-banking-service | `200`, `AccountBalanceResponse` |

`GET /api/customers/me` là API hỗ trợ ánh xạ người đăng nhập sang Customer. Chưa xây dựng API tạo hộ người khác, danh sách tài khoản, thay đổi trạng thái, giao dịch hoặc hệ thống cấp token.

Customer sở hữu bảng `customers`; Core sở hữu `accounts`, `currencies`, `account_status_history` và sequence số tài khoản. Core không đọc DB Customer, không dùng chung entity/repository/DTO Java giữa hai service.

## 2. Xác thực và xác định chủ sở hữu

Tất cả năm API yêu cầu `Authorization: Bearer <access-token>`. Gateway và backend xác thực token độc lập: chữ ký/thuật toán cho phép, thời hạn và issuer/audience theo cấu hình tin cậy. `sub` bắt buộc có giá trị; môi trường tuần 2 dùng một issuer tin cậy. Không diễn giải `sub` thành UUID của Customer.

`customers.auth_subject` lưu nguyên giá trị `sub` của token đã xác thực, phân biệt hoa/thường và duy nhất. Không trim hoặc đổi chữ trong subject. Subject dài quá 100 ký tự không phù hợp schema hiện tại và bị từ chối ở bước xác thực (`401`); không cắt ngắn hay thay đổi subject. Hỗ trợ nhiều issuer cần một thiết kế định danh và migration riêng.

Luồng mở tài khoản:

```text
Client -> Gateway -> Core (xác thực JWT)
                       |
                       +-> Customer: GET /api/customers/me, cùng Bearer token
                       |      xác thực JWT -> tìm customers.auth_subject = sub
                       |      trả Customer.id và status trong CustomerResponse
                       |
                       +-> kiểm tra Customer ACTIVE, currency và productType
                       +-> transaction Core: sinh số tài khoản + lưu Account + lịch sử
```

- Tạo Customer: service lấy subject từ danh tính đã xác thực, không nhận `authSubject` từ request.
- Mở Account: Core lấy `customerId` từ kết quả `/me`, không nhận `customerId` từ request.
- Xem Account/số dư: Core cũng resolve Customer qua `/me`, rồi kiểm tra `accounts.customer_id` bằng Customer đã xác minh. Kiểm tra nằm trong service, không chỉ ở controller.
- Account không tồn tại, không thuộc người gọi hoặc là tài khoản SYSTEM đều trả cùng `404 ACCOUNT_NOT_FOUND`. Người gọi chưa có Customer cũng nhận cùng lỗi này khi xem Account/số dư.
- `/me` trả hồ sơ của chính người gọi kể cả khi Customer `SUSPENDED`/`CLOSED`. Trạng thái này chặn mở Account mới; tuần 2 vẫn cho xem Account đã sở hữu.
- `X-User-Id`, `X-User-Role`, `X-Internal-Secret` do client gửi không chứng minh danh tính/quyền sở hữu. Giữ việc Gateway loại bỏ chúng; backend không dùng chúng thay JWT.
- Không dùng email để suy ra owner. Không log token, subject, email, số điện thoại hoặc body chứa dữ liệu cá nhân.

## 3. Quy tắc JSON, tiền và header

- Request/response thành công: `application/json`; lỗi backend: `application/problem+json`.
- JSON dùng camelCase; ID là chuỗi UUID; thời điểm là ISO-8601 UTC. Field nullable trong response được giữ với giá trị `null`.
- POST chỉ chấp nhận field được liệt kê trong hợp đồng. Field lạ, gồm `id`, `customerId`, `authSubject`, `status`, `balance`, `balanceMinor`, `version`, `createdAt`, gây `400 MALFORMED_REQUEST`; không âm thầm nhận hoặc bỏ qua owner/status do client gửi.
- Thành công khi tạo có header `Location`. Response thành công của năm API dùng `Cache-Control: no-store`.
- `balanceMinor` là chuỗi số nguyên không âm, ví dụ `"0"`, `"125000"`; không có phần thập phân hoặc dấu phân cách hàng nghìn. Backend vẫn lưu `long`/`BIGINT`; không đổi kiểu DB, không dùng `double`/`float`.
- `minorUnit` là số chữ số thập phân của currency, lấy từ bảng `currencies`; giá trị tiền theo đơn vị chính bằng `balanceMinor / 10^minorUnit`. VND: `minorUnit=0`, `"125000"` là 125.000 đồng. USD: `minorUnit=2`, `"12345"` là 123,45 USD.
- Chuỗi tiền tránh mất độ chính xác khi client dùng kiểu number không chứa chính xác toàn bộ miền `long`. Client phải dùng kiểu số nguyên/decimal chính xác khi tính toán. Không làm tròn hoặc đổi đơn vị số dư đang lưu khi đọc API.

## 4. Customer API

### POST /api/customers

Request `CreateCustomerRequest`:

```json
{
  "fullName": "Nguyen Van A",
  "email": "customer@example.com",
  "phone": "+84901234567"
}
```

| Field | Bắt buộc | Validation và chuẩn hóa |
|---|---|---|
| `fullName` | Có | String, trim, không trắng, tối đa 200 ký tự sau trim; giữ nguyên chữ/dấu |
| `email` | Có | String, trim, không trắng, email hợp lệ, tối đa 255 ký tự sau trim; lưu lowercase bằng `Locale.ROOT` |
| `phone` | Không | Thiếu/null/trắng sau trim lưu null; nếu có giá trị thì khớp `^[+]?[0-9]{8,15}$` |

Một subject chỉ tạo một Customer. Email duy nhất không phân biệt hoa/thường. Kiểm tra thân thiện ở service đi kèm unique constraint DB để xử lý yêu cầu đồng thời; email không phải bằng chứng xác thực danh tính. Nếu cả subject và email đã tồn tại, ưu tiên báo Customer của subject đã có.

Response `201 Created`, `Location: /api/customers/me` — đây là URL đọc hồ sơ vừa tạo với cùng danh tính; không dẫn tới một endpoint `/api/customers/{id}` chưa có:

```json
{
  "id": "10000000-0000-0000-0000-000000000001",
  "fullName": "Nguyen Van A",
  "email": "customer@example.com",
  "phone": "+84901234567",
  "status": "ACTIVE",
  "createdAt": "2026-11-17T03:00:00Z",
  "updatedAt": "2026-11-17T03:00:00Z"
}
```

Server tự gán ID, subject, `ACTIVE`, timestamps và version. `authSubject` và version không xuất hiện trong response. Gửi lại POST sau khi đã tạo nhận `409`, không trả thêm một hồ sơ mới.

### GET /api/customers/me

Không có body hoặc tham số owner. Thành công trả `200` với cùng `CustomerResponse` như trên. Không có mapping từ subject sang Customer trả `404 CUSTOMER_NOT_FOUND`. Customer enum có `ACTIVE`, `SUSPENDED`, `CLOSED`.

Core sở hữu wire DTO `common.dto.external.customer.CustomerInfo`, chỉ cần `id` và `status` từ response này. Adapter bỏ qua các field response còn lại, không đưa PII vào domain Core. Provider và consumer không chia sẻ Java class.

## 5. Account API

### POST /api/accounts

Request `CreateAccountRequest`:

```json
{
  "productType": "CHECKING",
  "currency": "VND"
}
```

| Field | Bắt buộc | Validation |
|---|---|---|
| `productType` | Có | `CHECKING` hoặc `SAVINGS`, phân biệt hoa/thường; không có mặc định ngầm |
| `currency` | Có | String đúng ba chữ in hoa ASCII (`^[A-Z]{3}$`), tồn tại trong `currencies` |

Giá trị currency hiện được seed: VND và USD; bảng `currencies` là nguồn xác định hỗ trợ currency và minor unit. `usd` không tự đổi thành `USD`. Sai kiểu/enum product không nhận diện là `400 MALFORMED_REQUEST`; thiếu/null field hoặc currency sai định dạng là `400 VALIDATION_ERROR`; currency đúng định dạng nhưng không được hỗ trợ là `400 UNSUPPORTED_CURRENCY`.

Customer phải tồn tại và `ACTIVE`. Account mới có `accountType=CUSTOMER`, trạng thái `ACTIVE`, số dư 0. Client không chọn số tài khoản hoặc chủ sở hữu. Số tài khoản là chuỗi do sequence và thuật toán hiện có sinh ra; không chuyển sang kiểu số ở API.

Response `201 Created`, `Location: /api/accounts/20000000-0000-0000-0000-000000000001`:

```json
{
  "id": "20000000-0000-0000-0000-000000000001",
  "accountNumber": "880000000013",
  "customerId": "10000000-0000-0000-0000-000000000001",
  "accountType": "CUSTOMER",
  "productType": "CHECKING",
  "currency": "VND",
  "status": "ACTIVE",
  "balanceMinor": "0",
  "minorUnit": 0,
  "createdAt": "2026-11-17T03:05:00Z",
  "updatedAt": "2026-11-17T03:05:00Z",
  "closedAt": null
}
```

Không trả entity JPA hoặc version. Ghi Account và lịch sử mở trong cùng transaction Core; lỗi ghi lịch sử phải rollback Account. HTTP gọi Customer hoàn tất trước transaction ghi; không thực hiện remote I/O trong transaction DB.

Tuần 2 cho phép một Customer mở nhiều Account, kể cả cùng product/currency. POST này chưa có hợp đồng idempotency; mỗi request thành công tạo một Account. Gateway, adapter và client không tự retry POST sau timeout. Không coi sequence/unique account number là chống tạo trùng theo ý định người gọi.

### GET /api/accounts/{accountId}

`accountId` bắt buộc là UUID hợp lệ; sai định dạng trả `400 MALFORMED_REQUEST`. Không nhận owner trong query/body. Thành công trả `200` với `AccountResponse` như trên sau khi kiểm tra ownership. Account `FROZEN`/`CLOSED` vẫn đọc được bởi chủ sở hữu; `closedAt` khác null khi và chỉ khi `CLOSED`.

### GET /api/accounts/{accountId}/balance

Cùng quy tắc UUID và ownership. Response `200`, `AccountBalanceResponse`:

```json
{
  "accountId": "20000000-0000-0000-0000-000000000001",
  "currency": "VND",
  "status": "ACTIVE",
  "balanceMinor": "0",
  "minorUnit": 0
}
```

Account có `ACTIVE`, `FROZEN`, `CLOSED`. Đọc thông tin/số dư không phải giao dịch. Mọi thao tác giao dịch sau này phải kiểm tra Account `ACTIVE` tại domain/service; bước 6 kiểm thử quy tắc, còn chặn trong luồng giao dịch thực tế được nghiệm thu khi có luồng đó. Không thêm endpoint giao dịch hoặc thay đổi trạng thái trong bước 2.

## 6. Hợp đồng lỗi

Giữ business `ProblemDetail` của Core: `type`, `title`, `status`, `detail`, `errorCode`, `timestamp`; `instance` có thể được framework bổ sung là đường dẫn request. Customer sử dụng cùng hình dạng ở bước 3, nhưng sở hữu `BusinessException`/enum/handler riêng. Business exception chung, không tạo subclass chỉ để gắn mã/status/message.

```json
{
  "type": "https://api.thitkho.com/errors/customer_not_eligible",
  "title": "Business Rule Violation",
  "status": 409,
  "detail": "Customer is not active",
  "errorCode": "CUSTOMER_NOT_ELIGIBLE",
  "timestamp": "2026-11-17T03:05:00Z"
}
```

`errorCode` vẫn là tên ổn định, không đổi sang mã ngắn. Mã ngắn là metadata enum lấy qua `getCode()`; chưa thêm field response cho mã ngắn. `detail` không chứa dữ liệu cá nhân hoặc chi tiết SQL/host/stack trace. Client dựa vào `errorCode` và status, không phân nhánh theo message.

| Owner | errorCode | Mã ngắn | HTTP | Điều kiện | Hiện trạng |
|---|---|---|---|---|---|
| Core | `ACCOUNT_NOT_FOUND` | `ANF01` | 404 | Account không có/khác chủ/SYSTEM; người gọi chưa có Customer khi đọc Account | Đã có enum |
| Core | `CUSTOMER_NOT_ELIGIBLE` | `CNE01` | 409 | Mở Account khi chưa có Customer hoặc Customer không ACTIVE | Đã có enum |
| Core | `UNSUPPORTED_CURRENCY` | `UC01` | 400 | Currency đúng định dạng nhưng chưa hỗ trợ | Đã có enum |
| Customer | `CUSTOMER_NOT_FOUND` | `CNF01` | 404 | `/me` chưa có hồ sơ | Đã triển khai bước 3 |
| Customer | `CUSTOMER_ALREADY_EXISTS` | `CAE01` | 409 | Subject đã có hồ sơ, gồm xung đột đồng thời | Đã triển khai bước 3 |
| Customer | `CUSTOMER_EMAIL_ALREADY_EXISTS` | `CEA01` | 409 | Email đã có hồ sơ khác, gồm xung đột đồng thời | Đã triển khai bước 3 |
| Mỗi backend | `VALIDATION_ERROR` | `VE01` | 400 | Thiếu field bắt buộc, ràng buộc Bean Validation | Customer đã có; Core đã có bước 5 |
| Mỗi backend | `MALFORMED_REQUEST` | `MR01` | 400 | JSON sai, sai kiểu, enum/UUID không parse được, field không được phép | Customer đã có; Core đã có bước 5 |
| Mỗi backend | `UNAUTHENTICATED` | `UA01` | 401 | Thiếu/token sai/hết hạn/subject không hợp lệ | Customer đã có; Core adapter đã có bước 4, security handler bước 7 |
| Mỗi backend | `ACCESS_DENIED` | `AD01` | 403 | Đã xác thực nhưng thiếu quyền endpoint; không dùng cho Account khác chủ | Customer đã có; Core adapter đã có bước 4, security handler bước 7 |
| Mỗi backend | `DATA_CONFLICT` | `DC01` | 409 | Lỗi toàn vẹn dữ liệu; Customer phân biệt trùng subject/email trước khi dùng lỗi chung | Customer bước 3; Core bước 5 |
| Mỗi backend | `INTERNAL_ERROR` | `IE01` | 500 | Lỗi không mong đợi, nội dung chung | Customer bước 3; Core bước 5 |
| Core | `UPSTREAM_TIMEOUT` | `UT01` | 504 | Chờ Customer quá thời hạn | Đã triển khai bước 4 |
| Core | `CUSTOMER_SERVICE_UNAVAILABLE` | `CSU01` | 503 | Không kết nối được Customer hoặc Customer trả 5xx | Đã triển khai bước 4 |
| Core | `INVALID_CUSTOMER_RESPONSE` | `ICR01` | 502 | Customer trả thành công nhưng thiếu/sai ID hoặc status hợp lệ | Đã triển khai bước 4 |

Mã ngắn phải không rỗng và không trùng trong từng service, kể cả khi tách enum theo feature. Hai backend tự sở hữu các mã cross-cutting; không dùng chung jar nghiệp vụ. Chỉ các dòng ghi đã triển khai mới có source tương ứng; các dòng dự kiến chưa được thêm vào Core.

Validation và lỗi binding mới dùng `title: "Invalid Request Data"`, cùng field `errorCode`, `timestamp`; validation thêm `errors: [{"field":"email","message":"must be a well-formed email address"}]`, không chứa rejected value. Message mô tả có thể thay đổi. Security handler backend mới dùng `title: "Unauthorized"`/`"Forbidden"`, không tiết lộ lý do kiểm tra token chi tiết.

Giữ mapping `IllegalArgumentException` hiện có của Core: `400 INVALID_ARGUMENT`, title `Invalid Request Data`, không tự thêm timestamp hoặc đổi format cho handler đó. Lỗi validation/binding mới có handler tương ứng, không gom lỗi lập trình thành lỗi đầu vào. Không thay cách xử lý lỗi DB/timeout đã có chỉ để đổi loại business exception; bảng upstream định nghĩa hành vi của adapter bước 4.

Gateway hiện có lỗi riêng dạng `{"status":401,"message":"Missing token"}` với `application/json`. Bước 2 không đổi format này. Client phải dựa vào HTTP status cho lỗi bị chặn tại Gateway; bảng ProblemDetail nói về backend. Đồng bộ khóa/issuer/audience và route ở bước 7; việc đổi response Gateway cần phạm vi riêng, không ngầm coi đã thống nhất.

## 7. Hợp đồng adapter Customer và lỗi từng phần

Ở bước 4, port Core đã đổi từ `findById(customerId, bearerToken)` sang `findCurrentCustomer(bearerToken)` trả `Optional<CustomerInfo>`. Adapter gọi `GET /api/customers/me`, forward Bearer token đã xác thực và chỉ map `id`, `status`. Tham số Java là raw access token, adapter tự thêm tiền tố `Bearer `. Service Core nhận credential qua ranh giới tin cậy, không đọc owner do client cung cấp.

| Kết quả Customer | Core khi mở Account | Core khi đọc Account/số dư |
|---|---|---|
| `200`, Customer ACTIVE | Tiếp tục kiểm tra và ghi local | Kiểm tra Account owner |
| `200`, Customer SUSPENDED/CLOSED | `409 CUSTOMER_NOT_ELIGIBLE` | Vẫn kiểm tra Account owner |
| `404 CUSTOMER_NOT_FOUND` | `409 CUSTOMER_NOT_ELIGIBLE`, detail `Customer not found` | `404 ACCOUNT_NOT_FOUND` |
| `401` | `401 UNAUTHENTICATED` | `401 UNAUTHENTICATED` |
| `403` | `403 ACCESS_DENIED` | `403 ACCESS_DENIED` |
| Connect lỗi/5xx | `503 CUSTOMER_SERVICE_UNAVAILABLE`, không ghi Account | Cùng lỗi; không bỏ qua kiểm tra owner |
| Timeout | `504 UPSTREAM_TIMEOUT`, không ghi Account | Cùng lỗi; không trả dữ liệu khi chưa xác minh owner |
| Response thành công sai schema/status | `502 INVALID_CUSTOMER_RESPONSE` | Cùng lỗi; không trả dữ liệu |

Không forward nguyên body lỗi upstream hoặc đổi mọi lỗi thành Customer không hợp lệ. HTTP dùng timeout hữu hạn từ `app.customer-service`; mặc định hiện tại connect 2 giây, read 30 giây, không tự retry trong tuần 2. Adapter không follow redirect để bảo vệ token. Customer bị đổi trạng thái sau lúc kiểm tra là khả năng của hệ thống phân tán: kiểm tra HTTP không khóa DB Customer. Không giữ transaction Core chờ HTTP hoặc tuyên bố transaction bao trùm hai DB.

## 8. DTO và nghiệm thu cho các bước tiếp theo

DTO đã triển khai ở bước 3–5, mỗi service tự sở hữu dưới root package của mình:

| Service | Package suffix | DTO |
|---|---|---|
| Customer | `common.dto.customer` | `CreateCustomerRequest`, `CustomerResponse` |
| Core | `common.dto.account` | `CreateAccountRequest`, `AccountResponse`, `AccountBalanceResponse` |
| Core | `common.dto.external.customer` | `CustomerInfo` (`id`, `status`) |

Các response Account/số dư cần cả giá trị `long` và metadata currency được map thành DTO trong transaction đọc; không trả entity. Dependency dùng đầy đủ tên kiểu như `currencyRepository`, `customerGateway`, `transactionTemplate`.

| Nhóm | Trường hợp cần kiểm thử ở bước triển khai |
|---|---|
| Customer | Tạo thành công, trim/normalize, thiếu/sai input, trùng subject/email và race unique constraint |
| Identity | JWT thiếu/sai/hết hạn, subject không phải UUID vẫn ánh xạ đúng, không dùng owner từ header/body |
| Account create | Customer ACTIVE/không có/SUSPENDED/CLOSED, currency hỗ trợ/không hỗ trợ, product không hợp lệ, trạng thái ban đầu/số dư 0 |
| Ownership | Chủ được xem; người khác/Account không có/SYSTEM cùng `404`; không có Customer cũng cùng `404` |
| Money/status | VND scale 0, USD scale 2, số dư lớn vượt miền số nguyên chính xác của JavaScript vẫn giữ nguyên chuỗi, FROZEN/CLOSED vẫn đọc được |
| Persistence | Account và history cùng commit/rollback; không có HTTP trong transaction local |
| Adapter | 404/401/403/5xx/connect/timeout/response lỗi theo bảng; không tiếp tục khi chưa xác minh Customer |
| Error contract | Giữ `ANF01`/`CNE01`/`UC01`, status, message hiện có, trường errorCode/timestamp và tính duy nhất của mã ngắn |

Adapter Core mặc định trỏ Customer cổng 4001; local config cũ dùng 8081 cần đổi theo hướng dẫn tích hợp. Gateway chưa route `/api/accounts/**`; cấu hình JWT Gateway/Core/Customer cần được đồng bộ ở bước 7. Customer đã có resource-server security yêu cầu key/issuer/audience từ môi trường. Tài liệu này không chứng minh toàn hệ thống đã chạy end-to-end và không thay thế kiểm thử ở bước 8.
