# Customer Management — Tuần 2, bước 3

Customer service cung cấp `POST /api/customers` và `GET /api/customers/me` theo [hợp đồng API](api/customer-account-contract.md). Service tự xác thực JWT, ánh xạ subject sang Customer và sở hữu DB/migration riêng.

## Cấu trúc mã nguồn

```text
web/CustomerController.java
mapper/CustomerMapper.java                         # MapStruct, Spring bean
common/exception/BusinessException.java
common/exception/ErrorCode.java
common/exception/handler/GlobalExceptionHandler.java # @Slf4j, xử lý exception MVC
common/web/ProblemDetailFactory.java                 # tạo response lỗi dùng chung
security/handler/SecurityProblemHandler.java         # xử lý lỗi trong security filter chain
```

`web` chỉ chứa controller. MapStruct sinh implementation trong `target/generated-sources/annotations`, kiểm tra mọi field response được map với `ReportingPolicy.ERROR`; dùng thêm `lombok-mapstruct-binding` để đọc các getter do Lombok sinh. Service nhận mapper qua constructor; không viết hoặc commit implementation được sinh tự động.

## Cấu hình và chạy local

`application.yml` là cấu hình local đang được git-ignore trong dự án. Mẫu không chứa secret thật nằm ở [application.yml.example](../customer-service/src/main/resources/application.yml.example). Với checkout mới, sao chép mẫu; với file local đã có, giữ các giá trị kết nối và bổ sung cấu hình còn thiếu.

Từ repository root, chuẩn bị PostgreSQL:

```powershell
docker compose up -d customer-postgres
Set-Location customer-service
if (-not (Test-Path src/main/resources/application.yml)) {
    Copy-Item src/main/resources/application.yml.example src/main/resources/application.yml
}
```

Chỉ chạy `Copy-Item` khi chưa có file local; không ghi đè cấu hình cá nhân. Đặt các biến môi trường trong shell chạy ứng dụng:

```powershell
$env:DB_USERNAME = '<database-user>'
$env:DB_PASSWORD = '<database-password>'
$env:APP_SECURITY_JWT_SECRET = '<shared-HS256-key-at-least-32-characters>'
$env:APP_SECURITY_JWT_ISSUER = '<trusted-issuer>'
$env:APP_SECURITY_JWT_AUDIENCE = '<expected-audience>'
.\mvnw.cmd spring-boot:run
```

DB mặc định của mẫu là `localhost:5437/customer_db`; dùng credential local theo Docker Compose hoặc DB của bạn. Có thể đặt `DB_URL`, `KAFKA_BOOTSTRAP_SERVERS`. Bước này không có producer/listener nghiệp vụ Kafka; không cần Kafka để tạo hoặc đọc Customer, nhưng context test có sẵn vẫn khởi động Kafka riêng bằng Testcontainers.

JWT dùng HS256, secret UTF-8 thô, không giải mã Base64. Token phải được ký bằng đúng key và có issuer, audience, thời hạn, subject hợp lệ. Subject là chuỗi opaque không trắng, tối đa 100 ký tự; không cần là UUID. Chưa có endpoint cấp token trong phạm vi này. Key, issuer và audience phải thống nhất với bên cấp token; đồng bộ toàn Gateway/Core là bước 7.

Port mặc định: `4001`. Backend xác thực trực tiếp; thêm `X-User-Id` hoặc `X-Internal-Secret` không thay thế Bearer token.

## Gọi API trực tiếp

Trong PowerShell, đặt token đã được cấp trong biến môi trường `ACCESS_TOKEN`, rồi gọi:

```powershell
$headers = @{ Authorization = "Bearer $env:ACCESS_TOKEN" }
$body = @{ fullName = 'Nguyen Van A'; email = 'customer@example.com'; phone = '+84901234567' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://localhost:4001/api/customers -Headers $headers -ContentType application/json -Body $body
Invoke-RestMethod -Method Get -Uri http://localhost:4001/api/customers/me -Headers $headers
```

POST trả `201`, `Location: /api/customers/me`, trạng thái `ACTIVE`; GET trả `200` khi hồ sơ tồn tại. Hai response có `Cache-Control: no-store`. `phone` không được cung cấp hoặc trắng được trả `null`. Không trả auth subject hoặc version.

Request tạo chỉ có `fullName`, `email`, `phone`. Tên/email được trim trước validation; email lưu lowercase. Field owner/status hoặc field lạ bị từ chối `400 MALFORMED_REQUEST`. Dữ liệu sai ràng buộc trả `400 VALIDATION_ERROR`, kèm tên field/message nhưng không chứa giá trị bị từ chối.

## Transaction và lỗi

- Tạo hồ sơ dùng transaction local ngắn, `saveAndFlush` để phát hiện constraint trước khi trả thành công. Khi request đồng thời đụng unique constraint, transaction ghi rollback trước rồi mới kiểm tra winner để trả đúng lỗi nghiệp vụ. Subject trùng được ưu tiên hơn email trùng.
- Flyway V1 hiện có được giữ nguyên; entity được Hibernate kiểm tra với `ddl-auto=validate`, `open-in-view=false`. Không có migration mới hoặc truy vấn dữ liệu service khác.
- Dùng `BusinessException` chung và enum `ErrorCode`: `CNF01`, `CAE01`, `CEA01` cùng các mã validation/security/technical. Response dùng tên `errorCode`, HTTP status và ProblemDetail như hợp đồng; mã ngắn là metadata enum.
- Logger `org.hibernate.orm.jdbc.error` được tắt trong mẫu để unique-conflict không ghi email vào log. Handler DB trả nội dung chung; không trả SQL hoặc lỗi decoder cho client.

## Kiểm thử

Yêu cầu JDK 25 và Docker Engine đang chạy. Từ `customer-service`:

```powershell
.\mvnw.cmd clean test
```

Test dùng key/issuer/audience riêng trong `application-test.yml`, không dùng key thật. Có unit test service, test decoder bằng JWT ký thật, MVC slice test, test tích hợp với PostgreSQL 15 và context test PostgreSQL/Kafka. Test tích hợp kiểm tra tạo → đọc, cách ly subject, constraint email không phân biệt hoa/thường và hai request tạo đồng thời.

Bước 3 chưa triển khai HTTP adapter của Core hoặc route Account; tiếp tục các phần đó sau khi được xác nhận sang bước 4/7.
